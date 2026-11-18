package com.example.hotrelay.protocol;

/**
 * Constantes du protocole HRP v1.0 (voir docs/protocole.md).
 * Toutes les valeurs « magiques » du protocole sont ici et nulle part ailleurs.
 */
public final class Hrp {

    private Hrp() {}

    // ---- En-tête (§5) ----
    public static final byte[] MAGIC = {0x48, 0x52, 0x50}; // "HRP"
    public static final int VERSION = 0x01;
    public static final int HEADER_SIZE = 8;

    // ---- Limites (§1, §13) ----
    public static final int PORT = 50000;
    public static final int MAX_DATA = 1024;
    public static final int MAX_PAYLOAD = 12 + MAX_DATA; // TAKE plein = 1036
    public static final int MAX_DATAGRAM = HEADER_SIZE + MAX_PAYLOAD; // 1044
    public static final int MAX_NAME = 32;
    public static final int MAX_FILE_NAME = 255;
    public static final int MAX_PARTICIPANTS = 8;
    public static final int HOST_ID = 0;
    public static final int SHA256_SIZE = 32;

    // ---- Types de messages (§6) ----
    public static final int NEW = 0x01;
    public static final int JOIN = 0x02;
    public static final int SYNCTABLE = 0x03;
    public static final int AVAILABLE = 0x04;
    public static final int BYE = 0x05;
    public static final int READY = 0x06;
    public static final int YES_READY = 0x07;
    public static final int TAKE = 0x08;
    public static final int NICE = 0x09;
    public static final int FINISH = 0x0A;
    public static final int COMPLETE = 0x0B;
    public static final int ERROR = 0x0C;
    public static final int CANCEL = 0x0D;
    public static final int EXIT = 0x0E;

    // ---- Codes d'erreur (§11) ----
    public static final int ERR_INVALID_MESSAGE = 0x0001;
    public static final int ERR_UNKNOWN_PARTICIPANT = 0x0002;
    public static final int ERR_UNKNOWN_TRANSFER = 0x0003;
    public static final int ERR_TRANSFER_REJECTED = 0x0004;
    public static final int ERR_HASH_MISMATCH = 0x0005;
    public static final int ERR_TRANSFER_TIMEOUT = 0x0006;
    public static final int ERR_TRANSFER_FAILED = 0x0007;
    public static final int ERR_INVALID_SEQUENCE = 0x0008;
    public static final int ERR_SESSION_FULL = 0x0009;

    // ---- Délais (§8, §10) ----
    public static final long RTO_INITIAL_MS = 500;
    public static final long RTO_MAX_MS = 4000;
    public static final int MAX_RETRIES = 5;
    public static final long HEARTBEAT_MS = 5000;   // AVAILABLE et SYNCTABLE
    public static final long PEER_TIMEOUT_MS = 15000; // silence => parti
    public static final int CONTROL_REPEAT = 3;     // CANCEL et EXIT envoyés 3 fois

    /** Délai avant l'essai n (1 = premier renvoi) : 500 ms, 1 s, 2 s, 4 s, 4 s. */
    public static long retryDelayMs(int attempt) {
        long d = RTO_INITIAL_MS << Math.max(0, attempt - 1);
        return Math.min(d, RTO_MAX_MS);
    }

    /** Taille minimale du payload pour chaque type (Annexe A), -1 si type inconnu. */
    public static int minPayload(int type) {
        switch (type) {
            case NEW: return 9;
            case JOIN: return 10;
            case SYNCTABLE: return 1;
            case AVAILABLE:
            case BYE:
            case EXIT: return 0;
            case READY: return 16;
            case YES_READY:
            case COMPLETE:
            case CANCEL: return 4;
            case TAKE: return 12;
            case NICE: return 8;
            case FINISH: return 36;
            case ERROR: return 6;
            default: return -1;
        }
    }

    /** true si le type porte un TRANSFER_ID et doit être relayé par l'hôte. */
    public static boolean isTransferMessage(int type) {
        return type >= READY && type <= COMPLETE || type == CANCEL || type == ERROR;
    }

    /** TRANSFER_ID = (ID émetteur × 2^24) + compteur (§3). */
    public static int transferId(int senderId, int counter) {
        return (senderId << 24) | (counter & 0xFFFFFF);
    }

    /** Premier octet du TRANSFER_ID = ID de l'émetteur. */
    public static int senderOf(int transferId) {
        return (transferId >>> 24) & 0xFF;
    }

    /** Nombre de blocs d'un fichier : (taille + 1023) / 1024. */
    public static long totalBlocks(long fileSize) {
        return (fileSize + MAX_DATA - 1) / MAX_DATA;
    }

    public static String typeName(int type) {
        switch (type) {
            case NEW: return "NEW";
            case JOIN: return "JOIN";
            case SYNCTABLE: return "SYNCTABLE";
            case AVAILABLE: return "AVAILABLE";
            case BYE: return "BYE";
            case READY: return "READY";
            case YES_READY: return "YES_READY";
            case TAKE: return "TAKE";
            case NICE: return "NICE";
            case FINISH: return "FINISH";
            case COMPLETE: return "COMPLETE";
            case ERROR: return "ERROR";
            case CANCEL: return "CANCEL";
            case EXIT: return "EXIT";
            default: return String.format("0x%02X", type);
        }
    }

    public static String errorName(int code) {
        switch (code) {
            case ERR_INVALID_MESSAGE: return "INVALID_MESSAGE";
            case ERR_UNKNOWN_PARTICIPANT: return "UNKNOWN_PARTICIPANT";
            case ERR_UNKNOWN_TRANSFER: return "UNKNOWN_TRANSFER";
            case ERR_TRANSFER_REJECTED: return "TRANSFER_REJECTED";
            case ERR_HASH_MISMATCH: return "HASH_MISMATCH";
            case ERR_TRANSFER_TIMEOUT: return "TRANSFER_TIMEOUT";
            case ERR_TRANSFER_FAILED: return "TRANSFER_FAILED";
            case ERR_INVALID_SEQUENCE: return "INVALID_SEQUENCE";
            case ERR_SESSION_FULL: return "SESSION_FULL";
            default: return String.format("0x%04X", code);
        }
    }
}
