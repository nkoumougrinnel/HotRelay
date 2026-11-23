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

    public Transfer(int transferId, boolean outgoing, int peerId, String fileName, long fileSize,
                    long blocksDone, long blocksTotal, State state, String error, String savedLocation) {
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
