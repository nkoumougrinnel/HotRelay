package com.example.hotrelay.transfer;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Enregistre les fichiers reçus dans Téléchargements (#20).
 *
 * <p>Android 10 et plus : {@code MediaStore.Downloads}, sans permission. Le fichier est marqué
 * {@code IS_PENDING} pendant l'écriture (invisible et non ouvrable), puis publié à la fin.
 * Android 8 et 9 : dossier Téléchargements propre à l'application.</p>
 */
public final class DownloadsStorage implements FileStorage {

    private static final String TAG = "DownloadsStorage";
    private static final String SUBFOLDER = "HotRelay";

    private final Context context;

    public DownloadsStorage(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public Output create(String fileName, long size) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return createMediaStore(fileName);
        return createLegacy(fileName);
    }

    // ---- Android 10+ ----

    private Output createMediaStore(String fileName) throws IOException {
        ContentResolver cr = context.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUBFOLDER);
        v.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("impossible de créer le fichier dans Téléchargements");
        OutputStream out = cr.openOutputStream(uri, "w");
        if (out == null) {
            cr.delete(uri, null, null);
            throw new IOException("impossible d'écrire dans Téléchargements");
        }
        return new Output() {
            @Override
            public OutputStream stream() {
                return out;
            }

            @Override
            public String commit() throws IOException {
                out.close();
                ContentValues done = new ContentValues();
                done.put(MediaStore.Downloads.IS_PENDING, 0);
                cr.update(uri, done, null, null);
                return uri.toString();
            }

            @Override
            public void abort() {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // on supprime de toute façon
                }
                cr.delete(uri, null, null);
                Log.i(TAG, "fichier partiel supprimé : " + fileName);
            }
        };
    }

    // ---- Android 8 et 9 ----

    private Output createLegacy(String fileName) throws IOException {
        File dir = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), SUBFOLDER);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("dossier introuvable : " + dir);
        File target = uniqueFile(dir, fileName);
        File part = new File(dir, target.getName() + ".part");
        FileOutputStream out = new FileOutputStream(part);
        return new Output() {
            @Override
            public OutputStream stream() {
                return out;
            }

            @Override
            public String commit() throws IOException {
                out.close();
                if (!part.renameTo(target)) throw new IOException("renommage impossible : " + target);
                return Uri.fromFile(target).toString();
            }

            @Override
            public void abort() {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // on supprime de toute façon
                }
                if (!part.delete()) Log.w(TAG, "suppression impossible : " + part);
            }
        };
    }

    /** « photo.jpg », sinon « photo (1).jpg », « photo (2).jpg »… */
    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; f.exists(); i++) f = new File(dir, base + " (" + i + ")" + ext);
        return f;
    }
}
