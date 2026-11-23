package com.example.hotrelay.transfer;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Où le destinataire écrit les fichiers reçus. Sur le téléphone :
 * {@link DownloadsStorage} (dossier Téléchargements) ; dans les tests : en mémoire.
 */
public interface FileStorage {

    /** Un fichier en cours d'écriture : invisible tant que {@link #commit()} n'est pas appelé. */
    interface Output {
        OutputStream stream();

        /** Fichier complet et vérifié : le rendre visible. Renvoie son emplacement. */
        String commit() throws IOException;

        /** Transfert raté : supprimer le fichier partiel (§11). */
        void abort();
    }

    Output create(String fileName, long size) throws IOException;
}
