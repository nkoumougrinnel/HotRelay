package com.example.hotrelay.transfer;

/**
 * Photo (non modifiable) d'un transfert à un instant donné, pour l'interface.
 * États du §9 du protocole, simplifiés pour l'affichage.
 */
public final class Transfer {

    public enum State {
        /** READY envoyé, on attend YES_READY. */
        WAITING_ACCEPT,
        /** Blocs en cours (TAKE ⇄ NICE). */
        SENDING,
        /** FINISH envoyé / reçu, vérification du SHA-256. */
        VERIFYING,
        COMPLETED,
        FAILED,
        CANCELLED;

        public boolean isFinished() {
            return this == COMPLETED || this == FAILED || this == CANCELLED;
        }
    }

    public final int transferId;
    /** true = ce téléphone envoie ; false = il reçoit. */
    public final boolean outgoing;
    /** ID de l'autre participant (destinataire si outgoing, sinon émetteur). */
    public final int peerId;
    public final String fileName;
    public final long fileSize;
    /** Blocs confirmés (émetteur : NICE reçus ; destinataire : blocs écrits). */
    public final long blocksDone;
    public final long blocksTotal;
    public final State state;
    /** Raison de l'échec, sinon null. */
    public final String error;
    /** Destinataire : emplacement du fichier enregistré (Uri en texte), sinon null. */
    public final String savedLocation;
    /** Émetteur : TAKE envoyés, renvois compris. Destinataire : TAKE reçus, doublons compris. */
    public final long packets;
    /** Émetteur : messages renvoyés faute de réponse. Destinataire : TAKE reçus en double. */
    public final long retransmissions;
    /** Début et fin (ms, horloge du téléphone) ; fin = 0 tant que ce n'est pas terminé. */
    public final long startedAt;
    public final long endedAt;
    /** Cases de la grille (voir {@link #GRID_CELLS}) où au moins un bloc a été renvoyé. */
    public final long retriedCells;

    /** Nombre de cases de la grille « PAQUETS » (chaque case regroupe plusieurs blocs). */
    public static final int GRID_CELLS = 60;

    public Transfer(int transferId, boolean outgoing, int peerId, String fileName, long fileSize,
                    long blocksDone, long blocksTotal, State state, String error, String savedLocation,
                    long packets, long retransmissions, long startedAt, long endedAt, long retriedCells) {
        this.transferId = transferId;
        this.outgoing = outgoing;
        this.peerId = peerId;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.blocksDone = blocksDone;
        this.blocksTotal = blocksTotal;
        this.state = state;
        this.error = error;
        this.savedLocation = savedLocation;
        this.packets = packets;
        this.retransmissions = retransmissions;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.retriedCells = retriedCells;
    }

    /** Octets confirmés. */
    public long bytesDone() {
        return Math.min(fileSize, blocksDone * com.example.hotrelay.protocol.Hrp.MAX_DATA);
    }

    /** Débit moyen en octets par seconde depuis le début (0 si trop tôt). */
    public long bytesPerSecond(long now) {
        long end = endedAt > 0 ? endedAt : now;
        long ms = end - startedAt;
        return ms < 200 ? 0 : bytesDone() * 1000 / ms;
    }

    /** Nombre de cases utilisées par la grille (moins de 60 pour les petits fichiers). */
    public int gridCells() {
        return (int) Math.max(1, Math.min(GRID_CELLS, blocksTotal));
    }

    /** Case de la grille qui contient le bloc {@code seq}. */
    public static int cellOf(long seq, long total) {
        int cells = (int) Math.max(1, Math.min(GRID_CELLS, total));
        return total <= 0 ? 0 : (int) Math.min(cells - 1, seq * cells / total);
    }

    /** Avancement de 0 à 100. */
    public int percent() {
        if (state == State.COMPLETED) return 100;
        if (blocksTotal <= 0) return 0;
        return (int) Math.min(100, blocksDone * 100 / blocksTotal);
    }

    @Override
    public String toString() {
        return String.format("%08X %s %s %d/%d %s", transferId, outgoing ? "→" : "←",
                fileName, blocksDone, blocksTotal, state) + (error != null ? " (" + error + ")" : "");
    }
}
