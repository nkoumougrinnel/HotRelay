package com.example.hotrelay.session;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.example.hotrelay.MainActivity;
import com.example.hotrelay.R;
import com.example.hotrelay.transfer.Transfer;

/**
 * Notification à la fin d'un transfert (#27) : envoyé, reçu ou échoué.
 * Un appui ouvre l'onglet Transferts.
 */
final class TransferNotifier {

    private static final String CHANNEL = "transfers";

    private final Context app;

    TransferNotifier(Context app) {
        this.app = app;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    app.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager nm = app.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    void onFinished(Transfer t, String peerName) {
        // Android 13+ : sans la permission, on ne notifie pas (le message court suffit).
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        String title;
        String text;
        if (t.state == Transfer.State.COMPLETED) {
            title = app.getString(t.outgoing ? R.string.notif_sent_title : R.string.notif_received_title);
            text = t.outgoing ? app.getString(R.string.notif_sent_text, t.fileName, peerName)
                    : app.getString(R.string.notif_received_text, t.fileName, peerName);
        } else if (t.state == Transfer.State.FAILED) {
            title = app.getString(R.string.notif_failed_title);
            text = app.getString(R.string.notif_failed_text, t.fileName, t.error);
        } else {
            return; // annulé : pas de notification
        }

        Intent open = new Intent(app, MainActivity.class)
                .putExtra(MainActivity.EXTRA_TAB, R.id.navigation_transfers)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(app, t.transferId, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_send)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true);
        try {
            NotificationManagerCompat.from(app).notify(t.transferId, b.build());
        } catch (SecurityException ignored) {
            // permission retirée entre-temps
        }
    }
}
