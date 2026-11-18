package com.example.hotrelay.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;

/** Tests du codec HRP (Annexe C du protocole). Lancer : ./gradlew test */
public class HrpPacketTest {

    private static byte[] hex(String s) {
        s = s.replace(" ", "");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    private static HrpPacket roundTrip(HrpPacket p) {
        HrpPacket back = HrpPacket.decode(p.encode());
        assertNotNull(back);
        assertEquals(p.type, back.type);
        assertArrayEquals(p.payload, back.payload);
        return back;
    }

    // ---- Exemple du §5 ----

    @Test
    public void takeHeaderMatchesSpecExample() {
        int tid = Hrp.transferId(1, 1);
        byte[] dg = new Messages.Take(tid, 0, 3, new byte[1024]).toPacket().encode();
        assertEquals(1044, dg.length);
        assertArrayEquals(hex("485250 01 08 00 040C 01000001 00000000 00000003"), Arrays.copyOf(dg, 20));
    }

    @Test
    public void transferIdLayout() {
        assertEquals(0x01000001, Hrp.transferId(1, 1));
        assertEquals(0x02000001, Hrp.transferId(2, 1));
        assertEquals(0x00000001, Hrp.transferId(0, 1));
        assertEquals(2, Hrp.senderOf(0x02000005));
    }

    @Test
    public void totalBlocksTable() {
        assertEquals(0, Hrp.totalBlocks(0));
        assertEquals(1, Hrp.totalBlocks(1));
        assertEquals(1, Hrp.totalBlocks(1024));
        assertEquals(2, Hrp.totalBlocks(1025));
        assertEquals(3, Hrp.totalBlocks(3000));
    }

    @Test
    public void retryDelays() {
        assertEquals(500, Hrp.retryDelayMs(1));
        assertEquals(1000, Hrp.retryDelayMs(2));
        assertEquals(2000, Hrp.retryDelayMs(3));
        assertEquals(4000, Hrp.retryDelayMs(4));
        assertEquals(4000, Hrp.retryDelayMs(5));
    }

    // ---- Aller-retour de chaque message ----

    @Test
    public void newAndJoin() throws Exception {
        Messages.New n = Messages.New.parse(roundTrip(new Messages.New(0x1122334455667788L, "Aïcha").toPacket()));
        assertEquals(0x1122334455667788L, n.requestId);
        assertEquals("Aïcha", n.name);
        assertEquals(8 + 1 + 6, new Messages.New(1, "Aïcha").toPacket().payload.length); // 6 octets UTF-8

        Messages.Join j = Messages.Join.parse(roundTrip(new Messages.Join(3, "Hôte", 42L).toPacket()));
        assertEquals(3, j.id);
        assertEquals("Hôte", j.hostName);
        assertEquals(42L, j.requestId);
    }

    @Test
    public void syncTable() throws Exception {
        Messages.SyncTable t = Messages.SyncTable.parse(roundTrip(new Messages.SyncTable(Arrays.asList(
                new Messages.Participant(0, "Hôte"),
                new Messages.Participant(1, "Paul"),
                new Messages.Participant(2, "Aïcha"))).toPacket()));
        assertEquals(3, t.participants.size());
        assertEquals("Aïcha", t.participants.get(2).name);
        assertEquals(2, t.participants.get(2).id);
    }

    @Test
    public void transferMessages() throws Exception {
        int tid = Hrp.transferId(1, 7);

        Messages.Ready r = Messages.Ready.parse(roundTrip(new Messages.Ready(1, 2, tid, "rapport.pdf", 3000).toPacket()));
        assertEquals(1, r.src);
        assertEquals(2, r.dst);
        assertEquals(tid, r.transferId);
        assertEquals("rapport.pdf", r.fileName);
        assertEquals(3000, r.fileSize);

        assertEquals(tid, Messages.YesReady.parse(roundTrip(new Messages.YesReady(tid).toPacket())).transferId);

        byte[] data = {1, 2, 3};
        Messages.Take tk = Messages.Take.parse(roundTrip(new Messages.Take(tid, 2, 3, data).toPacket()));
        assertEquals(2, tk.seq);
        assertEquals(3, tk.total);
        assertArrayEquals(data, tk.data);

        Messages.Nice n = Messages.Nice.parse(roundTrip(new Messages.Nice(tid, 2).toPacket()));
        assertEquals(2, n.seq);

        byte[] sha = new byte[32];
        sha[31] = 9;
        assertArrayEquals(sha, Messages.Finish.parse(roundTrip(new Messages.Finish(tid, sha).toPacket())).sha256);

        assertEquals(tid, Messages.Complete.parse(roundTrip(new Messages.Complete(tid).toPacket())).transferId);
        assertEquals(tid, Messages.Cancel.parse(roundTrip(new Messages.Cancel(tid).toPacket())).transferId);

        Messages.ErrorMessage e = Messages.ErrorMessage.parse(roundTrip(
                new Messages.ErrorMessage(Hrp.ERR_SESSION_FULL, 0).toPacket()));
        assertEquals(Hrp.ERR_SESSION_FULL, e.code);
        assertEquals(0, e.transferId);
    }

    @Test
    public void emptyMessages() {
        assertEquals(Hrp.AVAILABLE, roundTrip(Messages.available()).type);
        assertEquals(Hrp.BYE, roundTrip(Messages.bye()).type);
        assertEquals(Hrp.EXIT, roundTrip(Messages.exit()).type);
        assertEquals(8, Messages.exit().encode().length);
    }

    @Test
    public void transferIdReadWithoutDecoding() {
        int tid = Hrp.transferId(2, 5);
        assertEquals(tid, new Messages.Ready(2, 1, tid, "a", 1).toPacket().transferId());
        assertEquals(tid, new Messages.Take(tid, 0, 1, new byte[1]).toPacket().transferId());
        assertEquals(tid, new Messages.Nice(tid, 0).toPacket().transferId());
        assertEquals(tid, new Messages.ErrorMessage(Hrp.ERR_TRANSFER_FAILED, tid).toPacket().transferId());
        assertEquals(0, Messages.available().transferId());
    }

    @Test
    public void longNamesAreCutOnCharBoundary() throws Exception {
        String name = "ééééééééééééééééééééé"; // 21 × 2 octets = 42 > 32
        Messages.New n = Messages.New.parse(new Messages.New(1, name).toPacket());
        assertEquals(16, n.name.length()); // 32 octets = 16 « é »
    }

    // ---- Paquets invalides : ignorés, jamais de crash ----

    @Test
    public void rejectsInvalidDatagrams() {
        byte[] good = Messages.available().encode();
        assertNotNull(HrpPacket.decode(good));

        assertNull(HrpPacket.decode(new byte[0]));
        assertNull(HrpPacket.decode(Arrays.copyOf(good, 7)));            // tronqué
        assertNull(HrpPacket.decode("PING".getBytes()));                 // pas HRP

        byte[] b = good.clone(); b[0] = 'X';
        assertNull(HrpPacket.decode(b));                                 // MAGIC
        b = good.clone(); b[3] = 2;
        assertNull(HrpPacket.decode(b));                                 // VERSION
        b = good.clone(); b[4] = 0x0F;
        assertNull(HrpPacket.decode(b));                                 // TYPE inconnu
        b = good.clone(); b[5] = 1;
        assertNull(HrpPacket.decode(b));                                 // FLAGS
        b = Arrays.copyOf(good, 9);
        assertNull(HrpPacket.decode(b));                                 // taille ≠ 8 + LENGTH

        byte[] nice = new Messages.Nice(1, 0).toPacket().encode();
        nice[7] = 4; // LENGTH = 4 < minimum 8
        assertNull(HrpPacket.decode(Arrays.copyOf(nice, 12)));
    }

    @Test
    public void rejectsInconsistentPayloads() {
        // nom annoncé plus long que le payload
        expectMalformed(new HrpPacket(Hrp.NEW, hex("0000000000000001 05 4142")), Hrp.NEW);
        // UTF-8 invalide
        expectMalformed(new HrpPacket(Hrp.NEW, hex("0000000000000001 01 FF")), Hrp.NEW);
        // SEQ >= TOTAL
        expectMalformed(new Messages.Take(1, 3, 3, new byte[0]).toPacket(), Hrp.TAKE);
        // nom de fichier avec chemin
        expectMalformed(new Messages.Ready(1, 2, 0x01000001, "../x", 1).toPacket(), Hrp.READY);
        // octets en trop
        expectMalformed(new HrpPacket(Hrp.NICE, new byte[9]), Hrp.NICE);
    }

    private static void expectMalformed(HrpPacket p, int type) {
        try {
            switch (type) {
                case Hrp.NEW: Messages.New.parse(p); break;
                case Hrp.TAKE: Messages.Take.parse(p); break;
                case Hrp.READY: Messages.Ready.parse(p); break;
                case Hrp.NICE: Messages.Nice.parse(p); break;
                default: throw new IllegalArgumentException();
            }
            fail("paquet invalide accepté");
        } catch (Messages.MalformedPacketException expected) {
            // ok
        }
    }
}
