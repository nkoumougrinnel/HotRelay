package com.example.hotrelay.session;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.hotrelay.protocol.Hrp;
import com.example.hotrelay.protocol.HrpPacket;
import com.example.hotrelay.protocol.Messages;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Relais de l'hôte (#21) et transferts simultanés (#26), sur 127.0.0.1.
 */
public class RelayTest {

    private static final int PORT = 50556;

    private InetAddress localhost;
    private HostSession host;
    private final List<Peer> peers = new ArrayList<>();

    /**
     * Un client de test : garde les messages reçus dans une file, et peut jouer
     * automatiquement le rôle de destinataire (YES_READY, NICE, COMPLETE).
     */
    final class Peer {
        final ClientSession session;
        final LinkedBlockingQueue<HrpPacket> inbox = new LinkedBlockingQueue<>();
        final Map<Integer, ByteArrayOutputStream> received = new ConcurrentHashMap<>();
        final Map<Integer, Long> expectedSeq = new ConcurrentHashMap<>();
        volatile boolean autoReceive;
        volatile int id = -1;
        volatile int tableSize;

        Peer(String name) throws Exception {
            session = new ClientSession(name, localhost, PORT, new ClientSession.Listener() {
                @Override public void onJoined(int myId, String hostName) { id = myId; }
                @Override public void onParticipantsChanged(List<Messages.Participant> p) { tableSize = p.size(); }
                @Override public void onJoinFailed(String reason) {}
                @Override public void onDisconnected(String reason) {}
                @Override public void onTransferPacket(HrpPacket p) {
                    if (autoReceive && receive(p)) return;
                    inbox.add(p);
                }
            });
            session.start();
            peers.add(this);
        }

        /** Destinataire minimal Stop-and-Wait (§9). Renvoie true si le paquet a été traité. */
        private boolean receive(HrpPacket p) {
            try {
                switch (p.type) {
                    case Hrp.READY: {
                        Messages.Ready r = Messages.Ready.parse(p);
                        received.put(r.transferId, new ByteArrayOutputStream());
                        expectedSeq.put(r.transferId, 0L);
                        session.send(new Messages.YesReady(r.transferId).toPacket());
                        inbox.add(p);
                        return true;
                    }
                    case Hrp.TAKE: {
                        Messages.Take t = Messages.Take.parse(p);
                        Long exp = expectedSeq.get(t.transferId);
                        if (exp == null) return true;
                        if (t.seq == exp) {
                            received.get(t.transferId).write(t.data, 0, t.data.length);
                            expectedSeq.put(t.transferId, exp + 1);
                        }
                        if (t.seq <= exp) session.send(new Messages.Nice(t.transferId, t.seq).toPacket());
                        return true;
                    }
                    case Hrp.FINISH: {
                        session.send(new Messages.Complete(Messages.Finish.parse(p).transferId).toPacket());
                        return true;
                    }
                    default:
                        return false;
                }
            } catch (Messages.MalformedPacketException e) {
                throw new AssertionError(e);
            }
        }

        HrpPacket next(int type) throws InterruptedException {
            long end = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < end) {
                HrpPacket p = inbox.poll(50, TimeUnit.MILLISECONDS);
                if (p != null && p.type == type) return p;
            }
            return null;
        }

        /** Émetteur minimal Stop-and-Wait avec renvois (§10). */
        void sendFile(int dst, int tid, byte[] file) throws Exception {
            session.send(new Messages.Ready(id, dst, tid, "f.bin", file.length).toPacket());
            assertNotNull("YES_READY", waitFor(Hrp.YES_READY, tid, -1));
            long total = Hrp.totalBlocks(file.length);
            for (long seq = 0; seq < total; seq++) {
                int from = (int) (seq * Hrp.MAX_DATA);
                byte[] block = Arrays.copyOfRange(file, from, Math.min(file.length, from + Hrp.MAX_DATA));
                HrpPacket take = new Messages.Take(tid, seq, total, block).toPacket();
                boolean acked = false;
                for (int attempt = 0; attempt <= Hrp.MAX_RETRIES && !acked; attempt++) {
                    session.send(take);
                    acked = waitFor(Hrp.NICE, tid, seq) != null;
                }
                assertTrue("NICE " + seq, acked);
            }
            session.send(new Messages.Finish(tid, new byte[32]).toPacket());
            assertNotNull("COMPLETE", waitFor(Hrp.COMPLETE, tid, -1));
        }

        private HrpPacket waitFor(int type, int tid, long seq) throws Exception {
            long end = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < end) {
                HrpPacket p = inbox.poll(20, TimeUnit.MILLISECONDS);
                if (p == null || p.type != type || p.transferId() != tid) continue;
                if (seq >= 0 && Messages.Nice.parse(p).seq != seq) continue;
                return p;
            }
            return null;
        }
    }

    @Before
    public void setUp() throws Exception {
        localhost = InetAddress.getByName("127.0.0.1");
        host = new HostSession("Hôte", PORT, p -> {});
        host.start();
    }

    @After
    public void tearDown() {
        for (Peer p : peers) p.session.leave();
        host.stop();
    }

    private Peer[] connect(String... names) throws Exception {
        Peer[] out = new Peer[names.length];
        for (int i = 0; i < names.length; i++) out[i] = new Peer(names[i]);
        long end = System.currentTimeMillis() + 5000;
        for (Peer p : out) {
            while (p.tableSize != names.length + 1) {
                if (System.currentTimeMillis() > end) throw new AssertionError("connexion");
                Thread.sleep(20);
            }
        }
        return out;
    }

    private static int errorCode(HrpPacket p) throws Exception {
        return Messages.ErrorMessage.parse(p).code;
    }

    // #21 — critère : un message de A vers B arrive chez B avec src = id de A
    @Test
    public void readyReachesDestinationWithSenderId() throws Exception {
        Peer[] ab = connect("A", "B");
        Peer a = ab[0], b = ab[1];
        int tid = Hrp.transferId(a.id, 1);
        a.session.send(new Messages.Ready(a.id, b.id, tid, "photo.jpg", 5000).toPacket());

        HrpPacket got = b.next(Hrp.READY);
        assertNotNull(got);
        Messages.Ready r = Messages.Ready.parse(got);
        assertEquals(a.id, r.src);
        assertEquals(b.id, r.dst);
        assertEquals("photo.jpg", r.fileName);

        // et la réponse revient bien vers A
        b.session.send(new Messages.YesReady(tid).toPacket());
        assertNotNull(a.next(Hrp.YES_READY));
    }

    // #21 + #24 en miniature : un fichier complet A → hôte → B
    @Test
    public void fullTransferThroughHost() throws Exception {
        Peer[] ab = connect("A", "B");
        Peer a = ab[0], b = ab[1];
        b.autoReceive = true;
        byte[] file = new byte[10_000];
        new Random(1).nextBytes(file);
        int tid = Hrp.transferId(a.id, 1);
        a.sendFile(b.id, tid, file);
        assertArrayEquals(file, b.received.get(tid).toByteArray());
    }

    // #26 — A → B et C → A en même temps : les deux fichiers arrivent intacts
    @Test
    public void twoSimultaneousTransfersDoNotMix() throws Exception {
        Peer[] abc = connect("A", "B", "C");
        Peer a = abc[0], b = abc[1], c = abc[2];
        b.autoReceive = true;
        byte[] f1 = new byte[150_000];
        byte[] f2 = new byte[120_000];
        new Random(2).nextBytes(f1);
        new Random(3).nextBytes(f2);
        int t1 = Hrp.transferId(a.id, 1);
        int t2 = Hrp.transferId(c.id, 1);

        // A reçoit de C pendant qu'il envoie à B : il traite lui-même ses deux rôles
        a.autoReceive = true;
        AtomicReference<Throwable> err = new AtomicReference<>();
        Thread th1 = new Thread(() -> { try { a.sendFile(b.id, t1, f1); } catch (Throwable e) { err.set(e); } });
        Thread th2 = new Thread(() -> { try { c.sendFile(a.id, t2, f2); } catch (Throwable e) { err.set(e); } });
        th1.start();
        th2.start();
        th1.join(60_000);
        th2.join(60_000);
        if (err.get() != null) throw new AssertionError(err.get());
        assertArrayEquals(f1, b.received.get(t1).toByteArray());
        assertArrayEquals(f2, a.received.get(t2).toByteArray());
    }

    // §7 / §12 — SRC usurpé : refusé, rien n'arrive chez le destinataire
    @Test
    public void spoofedSourceIsRejected() throws Exception {
        Peer[] ab = connect("A", "B");
        Peer a = ab[0], b = ab[1];
        int tid = Hrp.transferId(b.id, 1); // A se fait passer pour B
        a.session.send(new Messages.Ready(b.id, b.id == 1 ? 2 : 1, tid, "x", 1).toPacket());
        HrpPacket e = a.next(Hrp.ERROR);
        assertNotNull(e);
        assertEquals(Hrp.ERR_INVALID_MESSAGE, errorCode(e));
        assertNull(b.next(Hrp.READY));
    }

    @Test
    public void unknownDestinationGetsError() throws Exception {
        Peer a = connect("A")[0];
        a.session.send(new Messages.Ready(a.id, 6, Hrp.transferId(a.id, 1), "x", 1).toPacket());
        HrpPacket e = a.next(Hrp.ERROR);
        assertNotNull(e);
        assertEquals(Hrp.ERR_UNKNOWN_PARTICIPANT, errorCode(e));
    }

    @Test
    public void unknownTransferGetsError() throws Exception {
        Peer a = connect("A")[0];
        a.session.send(new Messages.Take(Hrp.transferId(a.id, 9), 0, 1, new byte[1]).toPacket());
        HrpPacket e = a.next(Hrp.ERROR);
        assertNotNull(e);
        assertEquals(Hrp.ERR_UNKNOWN_TRANSFER, errorCode(e));
    }

    // §6 — seul l'émetteur envoie TAKE ; seul le destinataire envoie NICE
    @Test
    public void wrongDirectionIsDropped() throws Exception {
        Peer[] ab = connect("A", "B");
        Peer a = ab[0], b = ab[1];
        int tid = Hrp.transferId(a.id, 1);
        a.session.send(new Messages.Ready(a.id, b.id, tid, "x", 2000).toPacket());
        assertNotNull(b.next(Hrp.READY));
        b.session.send(new Messages.Take(tid, 0, 2, new byte[1]).toPacket()); // B n'est pas l'émetteur
        a.session.send(new Messages.Nice(tid, 0).toPacket());                  // A n'est pas le destinataire
        assertNull(a.next(Hrp.TAKE));
        assertNull(b.next(Hrp.NICE));
    }

    // §11 — un participant part pendant un transfert : l'autre reçoit TRANSFER_FAILED
    @Test
    public void leavingParticipantFailsTransfer() throws Exception {
        Peer[] ab = connect("A", "B");
        Peer a = ab[0], b = ab[1];
        int tid = Hrp.transferId(a.id, 1);
        a.session.send(new Messages.Ready(a.id, b.id, tid, "x", 2000).toPacket());
        assertNotNull(b.next(Hrp.READY));
        b.session.leave();
        peers.remove(b);
        HrpPacket e = a.next(Hrp.ERROR);
        assertNotNull(e);
        assertEquals(Hrp.ERR_TRANSFER_FAILED, errorCode(e));
        assertEquals(tid, e.transferId());
    }

    // L'hôte peut recevoir un fichier lui-même (DST = 0)
    @Test
    public void hostCanBeDestination() throws Exception {
        LinkedBlockingQueue<HrpPacket> hostInbox = new LinkedBlockingQueue<>();
        host.setLocalEndpoint(hostInbox::add);
        Peer a = connect("A")[0];
        int tid = Hrp.transferId(a.id, 1);
        a.session.send(new Messages.Ready(a.id, Hrp.HOST_ID, tid, "x", 1).toPacket());
        HrpPacket got = hostInbox.poll(3, TimeUnit.SECONDS);
        assertNotNull(got);
        assertEquals(Hrp.READY, got.type);
        host.sendFromHost(new Messages.YesReady(tid).toPacket());
        assertNotNull(a.next(Hrp.YES_READY));
    }
}
