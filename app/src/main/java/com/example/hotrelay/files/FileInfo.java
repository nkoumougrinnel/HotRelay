package com.example.hotrelay.files;

import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.example.hotrelay.protocol.Hrp;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Un fichier choisi par l'utilisateur (#7) : son adresse (Uri), son nom et sa taille.
 *
 * <p>On passe par le Storage Access Framework ({@code ACTION_OPEN_DOCUMENT}) :
 * pas besoin de permission de stockage, Android donne accès au seul fichier choisi.</p>
 */
public final class FileInfo {

    public final Uri uri;
    /** Nom seul, sans chemin (c'est ce qui part dans READY). */
    public final String name;
    /** Taille exacte en octets. */
    public final long size;

    public FileInfo(Uri uri, String name, long size) {
        this.uri = uri;
        this.name = name;
        this.size = size;
    }

    /**
     * Lit le nom et la taille d'un fichier choisi.
     *
     * @throws IOException si le fichier n'est plus accessible ou si sa taille est inconnue.
     */
    public static FileInfo from(ContentResolver resolver, Uri uri) throws IOException {
        String name = null;
        long size = -1;
        try (Cursor c = resolver.query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int iName = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int iSize = c.getColumnIndex(OpenableColumns.SIZE);
                if (iName >= 0 && !c.isNull(iName)) name = c.getString(iName);
                if (iSize >= 0 && !c.isNull(iSize)) size = c.getLong(iSize);
            }
        }
        if (size < 0) size = sizeFromDescriptor(resolver, uri); // certains fournisseurs ne donnent pas SIZE
        if (size < 0) throw new IOException("taille du fichier inconnue");
        return new FileInfo(uri, cleanName(name, uri), size);
    }

    private static long sizeFromDescriptor(ContentResolver resolver, Uri uri) {
        try (AssetFileDescriptor fd = resolver.openAssetFileDescriptor(uri, "r")) {
            return fd == null ? -1 : fd.getLength(); // -1 si inconnue
        } catch (IOException | SecurityException e) {
            return -1;
        }
    }

    /** Nom seul, sans « / » ni « \ », limité à 255 octets UTF-8 (règle du READY). */
    static String cleanName(String raw, Uri uri) {
        String n = raw;
        if (n == null || n.trim().isEmpty()) n = uri.getLastPathSegment();
        if (n == null || n.trim().isEmpty()) n = "fichier";
        n = n.trim();
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        if (n.isEmpty() || n.equals("..") || n.equals(".")) n = "fichier";
        while (n.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > Hrp.MAX_FILE_NAME) {
            n = n.substring(0, n.length() - 1);
        }
        return n;
    }

    /** Ouvre le contenu du fichier en lecture (à fermer après usage). */
    public InputStream open(ContentResolver resolver) throws FileNotFoundException {
        InputStream in = resolver.openInputStream(uri);
        if (in == null) throw new FileNotFoundException(uri.toString());
        return in;
    }

    /** Nombre de blocs TAKE pour ce fichier (§9). */
    public long blockCount() {
        return Hrp.totalBlocks(size);
    }

    /** « 2,4 Mo », « 840 Ko », « 512 o ». */
    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " o";
        if (bytes < 1024 * 1024) return String.format(Locale.FRANCE, "%.0f Ko", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.FRANCE, "%.1f Mo", bytes / (1024.0 * 1024));
        return String.format(Locale.FRANCE, "%.2f Go", bytes / (1024.0 * 1024 * 1024));
    }

    @Override
    public String toString() {
        return name + " (" + size + " o)";
    }
}
