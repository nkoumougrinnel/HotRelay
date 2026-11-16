package com.example.hotrelay.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.example.hotrelay.protocol.Hrp;
import com.example.hotrelay.protocol.HrpPacket;
import com.example.hotrelay.protocol.Messages;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Hôte + clients sur 127.0.0.1, délais raccourcis (100 ms / 600 ms au lieu de 5 s / 15 s).
 * Vérifie les critères de #15 et #17 sans téléphone.
 */
public class SessionTest {

    private static final int PORT = 50555;
    private static final long HEARTBEAT = 100;
    private static final long TIMEOUT = 600;

    private InetAddress localhost;
    private HostSession host;
    private final List<ClientSession> clients = new ArrayList<>();
    private volatile List<Messages.Participant> hostView = Collections.emptyList();

    /** Ce qu'un client a observé. */
    static final class Seen {
        volatile int id = -1;
        volatile List<Messages.Participant> table = Collections.emptyList();
        final AtomicReference<String> failed = new AtomicReference<>();
        final AtomicReference<String> disconnected = new AtomicReference<>();
    }

    @Before
    public void setUp() throws Exception {
        localhost = InetAddress.getByName("127.0.0.1");
        host = new HostSession("Hôte", PORT, new HostSession.Listener() {
            @Override
            public void onParticipantsChanged(List<Messages.Participant> p) {
                hostView = p;
            }
        });
        host.heartbeatMs = HEARTBEAT;
        host.timeoutMs = TIMEOUT;
        host.start();
    }

    @After
    public void tearDown() {
        for (ClientSession c : clients) c.leave();
        host.stop();
    }

    private ClientSession join(String name, Seen seen) throws Exception {
        ClientSession c = new ClientSession(name, localhost, PORT, new ClientSession.Listener() {
            @Override public void onJoined(int myId, String hostName) { seen.id = myId; }
            @Override public void onParticipantsChanged(List<Messages.Participant> p) { seen.table = p; }
            @Override public void onJoinFailed(String reason) { seen.failed.set(reason); }
            @Override public void onDisconnected(String reason) { seen.disconnected.set(reason); }
        });
        c.heartbeatMs = HEARTBEAT;
        c.timeoutMs = TIMEOUT;
        c.start();
        clients.add(c);
        return c;
    }

    private static void await(String what, BooleanSupplier cond) throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > end) throw new AssertionError("délai dépassé : " + what);
            Thread.sleep(20);
        }
    }

    // #15 : 2 clients enregistrés, IDs différents
    @Test
    public void twoClientsGetDistinctIds() throws Exception {
        Seen a = new Seen(), b = new Seen();
        join("Paul", a);
        join("Aïcha", b);
        await("2 JOIN", () -> a.id > 0 && b.id > 0);
        assertNotEquals(a.id, b.id);
        await("hôte voit 3 participants", () -> hostView.size() == 3);
        assertEquals(0, hostView.get(0).id);

        // #17 : chaque client connaît les autres (noms accentués compris)
        await("SYNCTABLE chez Paul", () -> a.table.size() == 3);
        await("SYNCTABLE chez Aïcha", () -> b.table.size() == 3);
        boolean found = false;
        for (Messages.Participant p : a.table) found |= p.name.equals("Aïcha");
        assertTrue(found);
    }

    // #17 : départ volontaire (BYE)
    @Test
    public void byeRemovesClientEverywhere() throws Exception {
        Seen a = new Seen(), b = new Seen();
        join("Paul", a);
        ClientSession cb = join("Aïcha", b);
        await("2 connectés", () -> a.table.size() == 3);
        cb.leave();
        clients.remove(cb);
        await("hôte : Aïcha partie", () -> hostView.size() == 2);
        await("Paul : Aïcha partie", () -> a.table.size() == 2);
    }

    // #17 : un client perd le Wi-Fi (pas de BYE) ⇒ retiré après le délai
    @Test
    public void silentClientExpires() throws Exception {
        Seen a = new Seen(), b = new Seen();
        join("Paul", a);
        ClientSession cb = join("Aïcha", b);
        await("2 connectés", () -> a.table.size() == 3);
        long cut = System.currentTimeMillis();
        cb.killForTest();
        clients.remove(cb);
        await("hôte : Aïcha expirée", () -> hostView.size() == 2);
        await("Paul : Aïcha expirée", () -> a.table.size() == 2);
        long took = System.currentTimeMillis() - cut;
        assertTrue("trop rapide : " + took, took >= TIMEOUT);
        assertTrue("trop lent : " + took, took < TIMEOUT * 2); // 15 s ⇒ < 30 s, critère #17 : < 15 s + marge
    }

    // §8 : l'hôte ferme ⇒ EXIT ⇒ clients déconnectés
    @Test
    public void hostExitDisconnectsClients() throws Exception {
        Seen a = new Seen();
        join("Paul", a);
        await("connecté", () -> a.id > 0);
        host.stop();
        await("EXIT reçu", () -> a.disconnected.get() != null);
    }

    // §8 : hôte disparu sans EXIT ⇒ client déconnecté après le délai
    @Test
    public void hostLostDetected() throws Exception {
        Seen a = new Seen();
        ClientSession c = join("Paul", a);
        await("connecté", () -> a.id > 0);
        // On coupe l'hôte brutalement : on ferme son socket sans EXIT.
        java.lang.reflect.Field f = HostSession.class.getDeclaredField("transport");
        f.setAccessible(true);
        ((com.example.hotrelay.network.UdpTransport) f.get(host)).close();
        await("hôte perdu", () -> a.disconnected.get() != null);
        assertEquals(ClientSession.State.CLOSED, c.getState());
    }

    // §7 : un NEW renvoyé (même REQUEST_ID) reçoit le même JOIN, sans second participant
    @Test
    public void duplicateNewGetsSameJoin() throws Exception {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(2000);
            byte[] dg = new Messages.New(77L, "Rejoue").toPacket().encode();
            int first = sendAndReadJoin(s, dg);
            int second = sendAndReadJoin(s, dg);
            assertEquals(first, second);
            await("1 seul client", () -> host.getRegistry().size() == 1);
            Thread.sleep(200);
            assertEquals(1, host.getRegistry().size());
        }
    }

    private int sendAndReadJoin(DatagramSocket s, byte[] dg) throws Exception {
        s.send(new DatagramPacket(dg, dg.length, localhost, PORT));
        byte[] buf = new byte[2048];
        while (true) {
            DatagramPacket in = new DatagramPacket(buf, buf.length);
            s.receive(in);
            HrpPacket p = HrpPacket.decode(java.util.Arrays.copyOf(buf, in.getLength()));
            if (p != null && p.type == Hrp.JOIN) {
                Messages.Join j = Messages.Join.parse(p);
                assertEquals(77L, j.requestId);
                return j.id;
            }
        }
    }

    // §7 : 7 clients max (IDs 1 à 7) ⇒ le 8e reçoit SESSION_FULL
    @Test
    public void eighthClientGetsSessionFull() throws Exception {
        List<Seen> seen = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            Seen s = new Seen();
            seen.add(s);
            join("C" + i, s);
        }
        await("7 connectés", () -> host.getRegistry().size() == 7);
        Seen extra = new Seen();
        join("Huitième", extra);
        await("refus", () -> extra.failed.get() != null);
        assertEquals("Session pleine", extra.failed.get());
    }

    // §10 : hôte absent ⇒ échec après les 5 renvois (~15,5 s) — test lent, désactivé par défaut
    // @Test
    public void joinFailsWhenNoHost() throws Exception {
        host.stop();
        Seen a = new Seen();
        join("Seul", a);
        long end = System.currentTimeMillis() + 17000;
        while (a.failed.get() == null && System.currentTimeMillis() < end) Thread.sleep(100);
        assertTrue(a.failed.get() != null);
    }
}
