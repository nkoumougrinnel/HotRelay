package com.example.hotrelay.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Les 14 messages HRP v1.0 (§7, Annexe A).
 *
 * <p>Chaque classe a :</p>
 * <ul>
 *   <li>un constructeur + {@code toPacket()} pour envoyer ;</li>
 *   <li>{@code parse(HrpPacket)} pour lire, qui lève {@link MalformedPacketException}
 *       si le contenu est incohérent (le paquet doit alors être ignoré).</li>
 * </ul>
 *
 * <pre>
 * byte[] datagramme = new Messages.Nice(tid, seq).toPacket().encode();
 *
 * HrpPacket p = HrpPacket.decode(data);
 * if (p != null &amp;&amp; p.type == Hrp.NICE) {
 *     Messages.Nice nice = Messages.Nice.parse(p);
 * }
 * </pre>
 */
public final class Messages {

    private Messages() {}

    /** Contenu de message incohérent : le paquet doit être ignoré. */
    public static final class MalformedPacketException extends Exception {
        public MalformedPacketException(String msg) { super(msg); }
    }

    // =====================================================================
    // Session
    // =====================================================================

    /** 0x01 NEW — client → hôte : demande d'entrée. */
    public static final class New {
        public final long requestId;
        public final String name;

        public New(long requestId, String name) {
            this.requestId = requestId;
            this.name = name;
        }

        public HrpPacket toPacket() {
            byte[] n = utf8(name, Hrp.MAX_NAME);
            Writer w = new Writer(8 + 1 + n.length);
            w.u64(requestId).u8(n.length).bytes(n);
            return w.packet(Hrp.NEW);
        }

        public static New parse(HrpPacket p) throws MalformedPacketException {
            Reader r = new Reader(p, Hrp.NEW);
            long req = r.u64();
            String name = r.string(r.u8(), Hrp.MAX_NAME);
            r.end();
            return new New(req, name);
        }
    }

    /** 0x02 JOIN — hôte → client : admission et ID attribué. */
    public static final class Join {
        public final int id;
        public final String hostName;
        public final long requestId;

        public Join(int id, String hostName, long requestId) {
            this.id = id;
            this.hostName = hostName;
            this.requestId = requestId;
        }

        public HrpPacket toPacket() {
            byte[] n = utf8(hostName, Hrp.MAX_NAME);
            Writer w = new Writer(1 + 1 + n.length + 8);
            w.u8(id).u8(n.length).bytes(n).u64(requestId);
            return w.packet(Hrp.JOIN);
        }

        public static Join parse(HrpPacket p) throws MalformedPacketException {
            Reader r = new Reader(p, Hrp.JOIN);
            int id = r.u8();
            String name = r.string(r.u8(), Hrp.MAX_NAME);
            long req = r.u64();
            r.end();
            if (id < 1 || id >= Hrp.MAX_PARTICIPANTS) throw new MalformedPacketException("ID invalide " + id);
            return new Join(id, name, req);
        }
    }

    /** Une ligne de la table des participants (sans IP ni port : jamais diffusés). */
    public static final class Participant {
        public final int id;
        public final String name;

        public Participant(int id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public String toString() { return id + ":" + name; }
    }

    /** 0x03 SYNCTABLE — hôte → tous : liste des participants (hôte compris). */
    public static final class SyncTable {
        public final List<Participant> participants;

        public SyncTable(List<Participant> participants) {
            this.participants = Collections.unmodifiableList(new ArrayList<>(participants));
        }

        public HrpPacket toPacket() {
            if (participants.size() > Hrp.MAX_PARTICIPANTS) throw new IllegalArgumentException("trop de participants");
            List<byte[]> names = new ArrayList<>();
            int size = 1;
            for (Participant pa : participants) {
                byte[] n = utf8(pa.name, Hrp.MAX_NAME);
                names.add(n);
                size += 2 + n.length;
            }
            Writer w = new Writer(size);
            w.u8(participants.size());
            for (int i = 0; i < participants.size(); i++) {
                w.u8(participants.get(i).id).u8(names.get(i).length).bytes(names.get(i));
            }
            return w.packet(Hrp.SYNCTABLE);
        }

        public static SyncTable parse(HrpPacket p) throws MalformedPacketException {
            Reader r = new Reader(p, Hrp.SYNCTABLE);
            int count = r.u8();
            if (count > Hrp.MAX_PARTICIPANTS) throw new MalformedPacketException("HOSTS=" + count);
            List<Participant> list = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int id = r.u8();
                list.add(new Participant(id, r.string(r.u8(), Hrp.MAX_NAME)));
            }
            r.end();
            return new SyncTable(list);
        }
    }

    // AVAILABLE, BYE et EXIT n'ont pas de payload.

    public static HrpPacket available() { return new HrpPacket(Hrp.AVAILABLE, new byte[0]); }

    public static HrpPacket bye() { return new HrpPacket(Hrp.BYE, new byte[0]); }

    public static HrpPacket exit() { return new HrpPacket(Hrp.EXIT, new byte[0]); }

    // =====================================================================
    // Transfert
    // =====================================================================

    /** 0x06 READY — émetteur → hôte → destinataire : annonce d'un fichier. */
    public static final class Ready {
        public final int src;
        public final int dst;
        public final int transferId;
        public final String fileName;
        public final long fileSize;

        public Ready(int src, int dst, int transferId, String fileName, long fileSize) {
            this.src = src;
            this.dst = dst;
            this.transferId = transferId;
            this.fileName = fileName;
            this.fileSize = fileSize;
        }

        public HrpPacket toPacket() {
            byte[] n = utf8(fileName, Hrp.MAX_FILE_NAME);
            Writer w = new Writer(1 + 1 + 4 + 2 + n.length + 8);
            w.u8(src).u8(dst).u32(transferId).u16(n.length).bytes(n).u64(fileSize);
            return w.packet(Hrp.READY);
        }

        public static Ready parse(HrpPacket p) throws MalformedPacketException {
            Reader r = new Reader(p, Hrp.READY);
            int src = r.u8();
            int dst = r.u8();
            int tid = r.u32();
            String name = r.string(r.u16(), Hrp.MAX_FILE_NAME);
            long size = r.u64();
            r.end();
            if (tid == 0) throw new MalformedPacketException("TRANSFER_ID = 0");
            if (size < 0) throw new MalformedPacketException("FILE_SIZE négatif");
            if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.equals("..")) {
                throw new MalformedPacketException("nom de fichier invalide");
            }
            return new Ready(src, dst, tid, name, size);
        }
    }

    /** 0x07 YES_READY — destinataire → émetteur : fichier accepté. */
    public static final class YesReady {
        public final int transferId;

        public YesReady(int transferId) { this.transferId = transferId; }

        public HrpPacket toPacket() { return tidOnly(Hrp.YES_READY, transferId); }

        public static YesReady parse(HrpPacket p) throws MalformedPacketException {
            return new YesReady(readTidOnly(p, Hrp.YES_READY));
        }
    }

    /** 0x08 TAKE — émetteur → destinataire : un bloc de données. */
    public static final class Take {
        public final int transferId;
        public final long seq;
        public final long total;
        public final byte[] data;

        public Take(int transferId, long seq, long total, byte[] data) {
            if (data.length > Hrp.MAX_DATA) throw new IllegalArgumentException("bloc > 1024 o");
            this.transferId = transferId;
            this.seq = seq;
            this.total = total;
            this.data = data;
        }

        public HrpPacket toPacket() {
            Writer w = new Writer(12 + data.length);
            w.u32(transferId).u32((int) seq).u32((int) total).bytes(data);
            return w.packet(Hrp.TAKE);
        }

        public static Take parse(HrpPacket p) throws MalformedPacketException {
            Reader r = new Reader(p, Hrp.TAKE);
            int tid = r.u32();
            long seq = r.u32() & 0xFFFFFFFFL;
            long total = r.u32() & 0xFFFFFFFFL;
            byte[] data = r.rest();
            if (data.length > Hrp.MAX_DATA) throw new MalformedPacketException("DATA > 1024");
            if (seq >= total) throw new MalformedPacketException("SEQ >= TOTAL");
            return new Take(tid, seq, total, data);
        }
    }

    /** 0x09 NICE — destinataire → émetteur : accusé de réception d'un bloc. */
    public static final class Nice {
        public final int transferId;
        public final long seq;

        public Nice(int transferId, long seq) {
            this.transferId = transferId;
            this.seq = seq;
        }

        public HrpPacket toPacket() {
            return new Writer(8).u32(transferId).u32((int) seq).packet(Hrp.NICE);
        }

        public static Nice parse(HrpPacket p) throws MalformedPacketException {
            Reader r = new Reader(p, Hrp.NICE);
            int tid = r.u32();
            long seq = r.u32() & 0xFFFFFFFFL;
            r.end();
            return new Nice(tid, seq);
        }
    }

    /** 0x0A FINISH — émetteur → destinataire : fin + SHA-256 du fichier. */
    public static final class Finish {
        public final int transferId;
        public final byte[] sha256;

        public Finish(int transferId, byte[] sha256) {
            if (sha256.length != Hrp.SHA256_SIZE) throw new IllegalArgumentException("SHA-256 = 32 o");
            this.transferId = transferId;
            this.sha256 = sha256;
        }

        public HrpPacket toPacket() {
            return new Writer(36).u32(transferId).bytes(sha256).packet(Hrp.FINISH);
        }

        public static Finish parse(HrpPacket p) throws MalformedPacketException {
            Reader r = new Reader(p, Hrp.FINISH);
            int tid = r.u32();
            byte[] sha = r.bytes(Hrp.SHA256_SIZE);
            r.end();
            return new Finish(tid, sha);
        }
    }

    /** 0x0B COMPLETE — destinataire → émetteur : fichier reçu et vérifié. */
    public static final class Complete {
        public final int transferId;

        public Complete(int transferId) { this.transferId = transferId; }

        public HrpPacket toPacket() { return tidOnly(Hrp.COMPLETE, transferId); }

        public static Complete parse(HrpPacket p) throws MalformedPacketException {
            return new Complete(readTidOnly(p, Hrp.COMPLETE));
        }
    }

    // =====================================================================
    // Contrôle
    // =====================================================================

    /** 0x0C ERROR — ERROR_CODE + TRANSFER_ID (0 hors transfert). */
    public static final class ErrorMessage {
        public final int code;
        public final int transferId;

        public ErrorMessage(int code, int transferId) {
            this.code = code;
            this.transferId = transferId;
        }

        public HrpPacket toPacket() {
            return new Writer(6).u16(code).u32(transferId).packet(Hrp.ERROR);
        }

        public static ErrorMessage parse(HrpPacket p) throws MalformedPacketException {
            Reader r = new Reader(p, Hrp.ERROR);
            int code = r.u16();
            int tid = r.u32();
            r.end();
            return new ErrorMessage(code, tid);
        }

        @Override
        public String toString() {
            return "ERROR " + Hrp.errorName(code) + String.format(" tid=%08X", transferId);
        }
    }

    /** 0x0D CANCEL — émetteur → destinataire : annulation (envoyé 3 fois). */
    public static final class Cancel {
        public final int transferId;

        public Cancel(int transferId) { this.transferId = transferId; }

        public HrpPacket toPacket() { return tidOnly(Hrp.CANCEL, transferId); }

        public static Cancel parse(HrpPacket p) throws MalformedPacketException {
            return new Cancel(readTidOnly(p, Hrp.CANCEL));
        }
    }

    // =====================================================================
    // Outils internes
    // =====================================================================

    private static HrpPacket tidOnly(int type, int tid) {
        return new Writer(4).u32(tid).packet(type);
    }

    private static int readTidOnly(HrpPacket p, int type) throws MalformedPacketException {
        Reader r = new Reader(p, type);
        int tid = r.u32();
        r.end();
        return tid;
    }

    /**
     * Encode en UTF-8 en coupant à {@code max} octets sans casser un caractère
     * (« Aïcha » = 6 octets, pas 5).
     */
    static byte[] utf8(String s, int max) {
        byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        if (b.length <= max) return b;
        int end = max;
        // recule tant qu'on est sur un octet de continuation 10xxxxxx
        while (end > 0 && (b[end] & 0xC0) == 0x80) end--;
        return Arrays.copyOf(b, end);
    }

    /** Écriture big-endian dans un tampon de taille connue. */
    private static final class Writer {
        private final ByteBuffer b;

        Writer(int size) { b = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN); }

        Writer u8(int v) { b.put((byte) v); return this; }
        Writer u16(int v) { b.putShort((short) v); return this; }
        Writer u32(int v) { b.putInt(v); return this; }
        Writer u64(long v) { b.putLong(v); return this; }
        Writer bytes(byte[] v) { b.put(v); return this; }

        HrpPacket packet(int type) { return new HrpPacket(type, b.array()); }
    }

    /** Lecture big-endian qui vérifie chaque longueur avant de lire (§12). */
    private static final class Reader {
        private final ByteBuffer b;

        Reader(HrpPacket p, int expectedType) throws MalformedPacketException {
            if (p.type != expectedType) {
                throw new MalformedPacketException("attendu " + Hrp.typeName(expectedType)
                        + ", reçu " + Hrp.typeName(p.type));
            }
            b = ByteBuffer.wrap(p.payload).order(ByteOrder.BIG_ENDIAN);
        }

        private void need(int n) throws MalformedPacketException {
            if (b.remaining() < n) throw new MalformedPacketException("payload tronqué");
        }

        int u8() throws MalformedPacketException { need(1); return b.get() & 0xFF; }
        int u16() throws MalformedPacketException { need(2); return b.getShort() & 0xFFFF; }
        int u32() throws MalformedPacketException { need(4); return b.getInt(); }
        long u64() throws MalformedPacketException { need(8); return b.getLong(); }

        byte[] bytes(int n) throws MalformedPacketException {
            need(n);
            byte[] out = new byte[n];
            b.get(out);
            return out;
        }

        byte[] rest() {
            byte[] out = new byte[b.remaining()];
            b.get(out);
            return out;
        }

        String string(int len, int max) throws MalformedPacketException {
            if (len > max) throw new MalformedPacketException("chaîne trop longue : " + len);
            byte[] raw = bytes(len);
            CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try {
                return dec.decode(ByteBuffer.wrap(raw)).toString();
            } catch (CharacterCodingException e) {
                throw new MalformedPacketException("UTF-8 invalide");
            }
        }

        void end() throws MalformedPacketException {
            if (b.hasRemaining()) throw new MalformedPacketException("octets en trop");
        }
    }
}
