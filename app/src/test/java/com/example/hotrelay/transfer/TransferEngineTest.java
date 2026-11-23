package com.example.hotrelay.transfer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.hotrelay.protocol.Hrp;
import com.example.hotrelay.protocol.HrpPacket;
import com.example.hotrelay.session.ClientSession;
import com.example.hotrelay.session.HostSession;
import com.example.hotrelay.session.SessionTestAccess;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Transferts complets A → hôte → B sur 127.0.0.1 (#22), avec pertes simulées (#25).
 */
public class TransferEngineTest {

    private static final int PORT = 50557;

    /** Stockage en mémoire : remplace le dossier Téléchargements. */
    static final class MemoryStorage implements FileStorage {
        final Map<String, byte[]> committed = new ConcurrentHashMap<>();
        final List<String> aborted = new ArrayList<>();

        @Override
        public Output create(String fileName, long size) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            return new Output() {
                @Override public OutputStream stream() { return buf; }
                @Override public String commit() { committed.put(fileName, buf.toByteArray()); return "mem:" + fileName; }
                @Override public void abort() { synchronized (aborted) { aborted.add(fileName); } }
            };
        }
    }

    /** Un participant : session + moteur + ce qu'il a vu. */
    final class Node {
        final MemoryStorage storage = new MemoryStorage();
        final Map<Integer, Transfer> last = new ConcurrentHashMap<>();
        TransferEngine engine;
        ClientSession client;
        volatile double dropRate;
        final Random random = new Random(42);

        /** Envoi avec pertes simulées. */
        void out(HrpPacket p, TransferEngine.PacketSender real) {
            synchronized (random) {
                if (random.nextDouble() < dropRate) return;
            }
            real.send(p);
        }

        Transfer await(int tid, Transfer.State state) throws InterruptedException {
            long end = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < end) {
                Transfer t = last.get(tid);
                if (t != null && t.state == state) return t;
                if (t != null && t.state.isFinished() && t.state != state) {
                    throw new AssertionError("attendu " + state + ", obtenu " + t);
                }
                Thread.sleep(20);
            }
            throw new AssertionError("délai dépassé, dernier état : " + last.get(tid));
        }
    }

    private InetAddress localhost;
    private HostSession host;
    private Node hostNode;
    private final List<Node> clients = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        localhost = InetAddress.getByName("127.0.0.1");
        hostNode = new Node();
        host = SessionTestAccess.newHost("Hôte", PORT);
        host.start();
        TransferEngine.PacketSender hostOut = host::sendFromHost;
        hostNode.engine = new TransferEngine(Hrp.HOST_ID, p -> hostNode.out(p, hostOut),
                hostNode.storage, t -> hostNode.last.put(t.transferId, t));
        host.setLocalEndpoint(hostNode.engine::onPacket);
    }

    @After
    public void tearDown() {
        for (Node n : clients) {
            n.engine.shutdown("fin du test");
            n.client.leave();
        }
        hostNode.engine.shutdown("fin du test");
        host.stop();
    }

    private Node join(String name) throws Exception {
        Node n = new Node();
        final int[] id = {-1};
        n.client = SessionTestAccess.newClient(name, localhost, PORT, new ClientSession.Listener() {
            @Override public void onJoined(int myId, String hostName) { id[0] = myId; }
            @Override public void onParticipantsChanged(List<com.example.hotrelay.protocol.Messages.Participant> p) {}
            @Override public void onJoinFailed(String reason) {}
            @Override public void onDisconnected(String reason) {}
            @Override public void onTransferPacket(HrpPacket p) {
                TransferEngine e = n.engine;
                if (e != null) e.onPacket(p);
            }
        });
        n.client.start();
        long end = System.currentTimeMillis() + 5000;
        while (id[0] < 0) {
            if (System.currentTimeMillis() > end) throw new AssertionError("JOIN");
            Thread.sleep(20);
        }
        TransferEngine.PacketSender real = n.client::send;
        n.engine = new TransferEngine(id[0], p -> n.out(p, real), n.storage, t -> n.last.put(t.transferId, t));
        clients.add(n);
        return n;
    }

    private static byte[] randomBytes(int size, long seed) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static TransferEngine.Source source(byte[] data) {
        return () -> new ByteArrayInputStream(data);
    }

    private int myId(Node n) {
        return n.client.getMyId();
    }

    // #22 — critère : 1 Mo de A vers B via l'hôte, contenu identique
    @Test
    public void oneMegabyteThroughHost() throws Exception {
        Node a = join("A"), b = join("B");
        byte[] file = randomBytes(1024 * 1024, 1);
        int tid = a.engine.send("photo.jpg", file.length, source(file), myId(b));
        a.await(tid, Transfer.State.COMPLETED);
        Transfer rx = b.await(tid, Transfer.State.COMPLETED);
        assertArrayEquals(file, b.storage.committed.get("photo.jpg"));
        assertEquals(1024, rx.blocksTotal);
        assertEquals(100, rx.percent());
        assertEquals("mem:photo.jpg", rx.savedLocation);
    }

    // Tailles limites du §9 : 0 o, 1 o, 1024 o, 1025 o
    @Test
    public void edgeSizes() throws Exception {
        Node a = join("A"), b = join("B");
        int[] sizes = {0, 1, 1024, 1025, 3000};
        for (int size : sizes) {
            byte[] file = randomBytes(size, size);
            String name = "f" + size + ".bin";
            int tid = a.engine.send(name, size, source(file), myId(b));
            b.await(tid, Transfer.State.COMPLETED);
            assertArrayEquals(name, file, b.storage.committed.get(name));
        }
    }

    // #25 — 20 % de paquets perdus dans les deux sens : le fichier arrive quand même intact
    @Test
    public void survivesPacketLoss() throws Exception {
        Node a = join("A"), b = join("B");
        a.dropRate = 0.2;
        b.dropRate = 0.2;
        byte[] file = randomBytes(60_000, 7);
        int tid = a.engine.send("perte.bin", file.length, source(file), myId(b));
        b.await(tid, Transfer.State.COMPLETED);
        a.await(tid, Transfer.State.COMPLETED);
        assertArrayEquals(file, b.storage.committed.get("perte.bin"));
    }

    // L'hôte envoie et reçoit lui aussi
    @Test
    public void hostSendsAndReceives() throws Exception {
        Node a = join("A");
        byte[] f1 = randomBytes(20_000, 3);
        int t1 = hostNode.engine.send("depuis_hote.bin", f1.length, source(f1), myId(a));
        a.await(t1, Transfer.State.COMPLETED);
        assertArrayEquals(f1, a.storage.committed.get("depuis_hote.bin"));

        byte[] f2 = randomBytes(20_000, 4);
        int t2 = a.engine.send("vers_hote.bin", f2.length, source(f2), Hrp.HOST_ID);
        hostNode.await(t2, Transfer.State.COMPLETED);
        assertArrayEquals(f2, hostNode.storage.committed.get("vers_hote.bin"));
    }

    // #26 — A → B et B → A en même temps
    @Test
    public void crossTransfers() throws Exception {
        Node a = join("A"), b = join("B");
        byte[] f1 = randomBytes(200_000, 5);
        byte[] f2 = randomBytes(150_000, 6);
        int t1 = a.engine.send("ab.bin", f1.length, source(f1), myId(b));
        int t2 = b.engine.send("ba.bin", f2.length, source(f2), myId(a));
        b.await(t1, Transfer.State.COMPLETED);
        a.await(t2, Transfer.State.COMPLETED);
        assertArrayEquals(f1, b.storage.committed.get("ab.bin"));
        assertArrayEquals(f2, a.storage.committed.get("ba.bin"));
    }

    // §11 — destinataire inconnu : échec propre chez l'émetteur
    @Test
    public void unknownRecipientFails() throws Exception {
        Node a = join("A");
        int tid = a.engine.send("x.bin", 10, source(new byte[10]), 6);
        Transfer t = a.await(tid, Transfer.State.FAILED);
        assertNotNull(t.error);
    }

    // §7 — CANCEL : le destinataire supprime le fichier partiel, rien n'est enregistré
    @Test
    public void cancelDeletesPartialFile() throws Exception {
        Node a = join("A"), b = join("B");
        byte[] file = randomBytes(2_000_000, 8);
        int tid = a.engine.send("annule.bin", file.length, source(file), myId(b));
        long end = System.currentTimeMillis() + 10_000;
        while (b.last.get(tid) == null || b.last.get(tid).blocksDone < 20) {
            if (System.currentTimeMillis() > end) throw new AssertionError("pas de progression");
            Thread.sleep(10);
        }
        a.engine.cancel(tid);
        b.await(tid, Transfer.State.CANCELLED);
        assertNull(b.storage.committed.get("annule.bin"));
        assertTrue(b.storage.aborted.contains("annule.bin"));
    }

    // Fichier illisible : échec immédiat côté émetteur, sans planter
    @Test
    public void unreadableSourceFails() throws Exception {
        Node a = join("A"), b = join("B");
        int tid = a.engine.send("x.bin", 10, () -> { throw new IOException("disparu"); }, myId(b));
        Transfer t = a.await(tid, Transfer.State.FAILED);
        assertTrue(t.error.contains("disparu"));
    }
}
