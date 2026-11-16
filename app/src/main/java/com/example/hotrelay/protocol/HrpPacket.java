package com.example.hotrelay.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Un datagramme HRP : en-tête de 8 octets + payload (§5).
 *
 * <p>{@link #decode} applique les 5 règles de validation du §5 et renvoie
 * {@code null} pour tout paquet invalide : un paquet réseau ne doit jamais
 * faire planter l'application.</p>
 *
 * <p>Le contenu de chaque type de message est lu et écrit par {@link Messages}.</p>
 */
public final class HrpPacket {

    public final int type;
    /** Payload seul (sans l'en-tête). Ne pas modifier. */
    public final byte[] payload;

    public HrpPacket(int type, byte[] payload) {
        if (payload.length > Hrp.MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload trop grand : " + payload.length);
        }
        this.type = type;
        this.payload = payload;
    }

    /** Sérialise en datagramme prêt à envoyer (en-tête + payload). */
    public byte[] encode() {
        ByteBuffer b = ByteBuffer.allocate(Hrp.HEADER_SIZE + payload.length).order(ByteOrder.BIG_ENDIAN);
        b.put(Hrp.MAGIC);
        b.put((byte) Hrp.VERSION);
        b.put((byte) type);
        b.put((byte) 0); // FLAGS
        b.putShort((short) payload.length);
        b.put(payload);
        return b.array();
    }

    /**
     * Valide et lit un datagramme reçu.
     *
     * @return le paquet, ou {@code null} s'il doit être ignoré.
     */
    public static HrpPacket decode(byte[] data, int length) {
        // 1. au moins 8 octets
        if (data == null || length < Hrp.HEADER_SIZE || length > data.length) return null;
        // 2. MAGIC, VERSION, FLAGS
        if (data[0] != Hrp.MAGIC[0] || data[1] != Hrp.MAGIC[1] || data[2] != Hrp.MAGIC[2]) return null;
        if ((data[3] & 0xFF) != Hrp.VERSION) return null;
        int type = data[4] & 0xFF;
        if (data[5] != 0) return null;
        // 3. TYPE connu
        int min = Hrp.minPayload(type);
        if (min < 0) return null;
        // 4. taille réelle = 8 + LENGTH
        int len = ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
        if (length != Hrp.HEADER_SIZE + len) return null;
        // 5. taille minimale du type, et maximum absolu
        if (len < min || len > Hrp.MAX_PAYLOAD) return null;
        return new HrpPacket(type, Arrays.copyOfRange(data, Hrp.HEADER_SIZE, length));
    }

    public static HrpPacket decode(byte[] data) {
        return data == null ? null : decode(data, data.length);
    }

    /**
     * TRANSFER_ID d'un message de transfert, lu directement dans le payload
     * sans décoder le reste (utile pour le relais de l'hôte).
     * Renvoie 0 si le type n'en porte pas.
     */
    public int transferId() {
        int off;
        switch (type) {
            case Hrp.READY: off = 2; break;              // SRC, DST, puis TRANSFER_ID
            case Hrp.ERROR: off = 2; break;              // ERROR_CODE, puis TRANSFER_ID
            case Hrp.YES_READY:
            case Hrp.TAKE:
            case Hrp.NICE:
            case Hrp.FINISH:
            case Hrp.COMPLETE:
            case Hrp.CANCEL: off = 0; break;
            default: return 0;
        }
        return ByteBuffer.wrap(payload, off, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    @Override
    public String toString() {
        int tid = transferId();
        return Hrp.typeName(type) + "(len=" + payload.length
                + (tid != 0 ? String.format(", tid=%08X", tid) : "") + ")";
    }
}
