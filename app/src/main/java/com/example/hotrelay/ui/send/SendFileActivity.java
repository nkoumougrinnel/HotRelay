package com.example.hotrelay.ui.send;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.example.hotrelay.R;
import com.example.hotrelay.files.FileInfo;
import com.example.hotrelay.model.Participant;
import com.example.hotrelay.session.SessionManager;
import com.example.hotrelay.transfer.Transfer;

import java.io.IOException;
import java.util.List;

/**
 * Écran Envoyer : choix du destinataire parmi les participants (#23) et du fichier (#7).
 *
 * <p>Le fichier est choisi avec le Storage Access Framework ({@code ACTION_OPEN_DOCUMENT}) :
 * aucune permission de stockage n'est nécessaire. L'envoi suit le protocole §9 (#22) et
 * sa progression s'affiche en direct.</p>
 */
public class SendFileActivity extends AppCompatActivity implements SessionManager.Listener {

    /** ID du destinataire à présélectionner (ex. appareil touché dans l'onglet Appareils). */
    public static final String EXTRA_RECIPIENT_ID = "recipient_id";

    private static final String TAG = "SendFile";
    private static final String STATE_URI = "file_uri";
    private static final String STATE_RECIPIENT = "recipient_id";
    private static final String STATE_TRANSFER = "transfer_id";

    private SessionManager session;
    private RadioGroup rgRecipients;
    private TextView tvNoRecipient, tvSendVia, tvSendStatus, tvFileName, tvFileMeta, tvFileAction, tvSendHint;
    private View btnSend, badgeStatus, cardProgress, btnCancelTransfer;
    private TextView tvProgressTitle, tvProgressPercent, tvProgressState;
    private ProgressBar pbSend;

    private FileInfo file;
    private String recipientId;
    /** Envoi lancé depuis cet écran, -1 si aucun. */
    private int transferId = -1;

    /** Ouvre le sélecteur de fichiers d'Android (tous les types). */
    private final ActivityResultLauncher<String[]> pickFile =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onFilePicked);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_send_file);
        session = SessionManager.get(this);

        rgRecipients = findViewById(R.id.rgRecipients);
        tvNoRecipient = findViewById(R.id.tvNoRecipient);
        tvSendVia = findViewById(R.id.tvSendVia);
        tvSendStatus = findViewById(R.id.tvSendStatus);
        badgeStatus = findViewById(R.id.badgeSendStatus);
        tvFileName = findViewById(R.id.tvFileName);
        tvFileMeta = findViewById(R.id.tvFileMeta);
        tvFileAction = findViewById(R.id.tvFileAction);
        tvSendHint = findViewById(R.id.tvSendHint);
        btnSend = findViewById(R.id.btnSendViaHost);
        cardProgress = findViewById(R.id.cardProgress);
        tvProgressTitle = findViewById(R.id.tvProgressTitle);
        tvProgressPercent = findViewById(R.id.tvProgressPercent);
        tvProgressState = findViewById(R.id.tvProgressState);
        pbSend = findViewById(R.id.pbSend);
        btnCancelTransfer = findViewById(R.id.btnCancelTransfer);
        btnCancelTransfer.setOnClickListener(v -> session.cancelTransfer(transferId));

        findViewById(R.id.btnBackSend).setOnClickListener(v -> finish());
        findViewById(R.id.cardFile).setOnClickListener(v -> pickFile.launch(new String[]{"*/*"}));
        btnSend.setOnClickListener(v -> send());
        rgRecipients.setOnCheckedChangeListener((g, checkedId) -> {
            View rb = g.findViewById(checkedId);
            recipientId = rb == null ? null : (String) rb.getTag();
            updateSendButton();
        });

        if (savedInstanceState != null) {
            recipientId = savedInstanceState.getString(STATE_RECIPIENT);
            transferId = savedInstanceState.getInt(STATE_TRANSFER, -1);
            Uri uri = savedInstanceState.getParcelable(STATE_URI);
            if (uri != null) loadFile(uri);
        } else {
            int id = getIntent().getIntExtra(EXTRA_RECIPIENT_ID, -1);
            if (id >= 0) recipientId = String.valueOf(id);
        }
        showFile();
    }

    @Override
    protected void onStart() {
        super.onStart();
        session.addListener(this);
        Transfer t = transferId == -1 ? null : session.getTransfer(transferId);
        if (t != null) onTransferChanged(t); // revenu sur l'écran : état à jour
    }

    @Override
    protected void onStop() {
        session.removeListener(this);
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(STATE_RECIPIENT, recipientId);
        out.putInt(STATE_TRANSFER, transferId);
        if (file != null) out.putParcelable(STATE_URI, file.uri);
    }

    // ------------------------------------------------------------------
    // Fichier (#7)
    // ------------------------------------------------------------------

    private void onFilePicked(Uri uri) {
        if (uri == null) return; // l'utilisateur a annulé
        // Garder le droit de lecture même si l'écran est recréé (rotation, retour en arrière-plan).
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            Log.d(TAG, "permission persistante refusée, accès temporaire seulement");
        }
        loadFile(uri);
        showFile();
    }

    private void loadFile(Uri uri) {
        try {
            file = FileInfo.from(getContentResolver(), uri);
            Log.i(TAG, "fichier choisi : " + file);
        } catch (IOException | SecurityException e) {
            file = null;
            Toast.makeText(this, getString(R.string.file_unreadable, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void showFile() {
        if (file == null) {
            tvFileName.setText(R.string.no_file_selected);
            tvFileMeta.setText(R.string.tap_to_browse);
            tvFileAction.setText(R.string.choose);
        } else {
            tvFileName.setText(file.name);
            tvFileMeta.setText(getString(R.string.file_meta, FileInfo.formatSize(file.size), file.blockCount()));
            tvFileAction.setText(R.string.change);
        }
        updateSendButton();
    }

    // ------------------------------------------------------------------
    // Destinataire (#23)
    // ------------------------------------------------------------------

    @Override
    public void onSessionChanged(SessionManager s) {
        boolean connected = s.getStatus() == SessionManager.Status.CONNECTED;
        tvSendStatus.setText(connected ? R.string.status_connected : R.string.status_offline);
        badgeStatus.setAlpha(connected ? 1f : 0.5f);

        if (!connected) {
            tvSendVia.setText(R.string.send_no_session);
        } else if (s.getRole() == SessionManager.Role.HOST) {
            tvSendVia.setText(R.string.send_as_host);
        } else {
            String ip = s.getHostAddress();
            tvSendVia.setText(ip == null ? getString(R.string.send_via_host_unknown)
                    : getString(R.string.send_via_host, ip));
        }
        showRecipients(connected ? s.getOthers() : java.util.Collections.emptyList());
    }

    /** Reconstruit la liste en gardant la sélection si le destinataire est toujours là. */
    private void showRecipients(List<Participant> others) {
        rgRecipients.setOnCheckedChangeListener(null);
        rgRecipients.removeAllViews();
        boolean stillThere = false;
        for (Participant p : others) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setTag(p.getId());
            rb.setText(getString(p.isHost() ? R.string.recipient_host_label : R.string.recipient_label,
                    p.getName(), p.getId()));
            rb.setTextSize(15);
            rb.setPadding(16, 24, 16, 24);
            rgRecipients.addView(rb);
            if (p.getId().equals(recipientId)) {
                rb.setChecked(true);
                stillThere = true;
            }
        }
        if (!stillThere) recipientId = null; // parti entre-temps
        tvNoRecipient.setVisibility(others.isEmpty() ? View.VISIBLE : View.GONE);
        rgRecipients.setOnCheckedChangeListener((g, checkedId) -> {
            View rb = g.findViewById(checkedId);
            recipientId = rb == null ? null : (String) rb.getTag();
            updateSendButton();
        });
        updateSendButton();
    }

    private String recipientName() {
        for (Participant p : session.getOthers()) {
            if (p.getId().equals(recipientId)) return p.getName();
        }
        return "?";
    }

    // ------------------------------------------------------------------
    // Envoi
    // ------------------------------------------------------------------

    private boolean sending() {
        Transfer t = transferId == -1 ? null : session.getTransfer(transferId);
        return transferId != -1 && (t == null || !t.state.isFinished());
    }

    private void updateSendButton() {
        boolean ready = file != null && recipientId != null && !sending()
                && session.getStatus() == SessionManager.Status.CONNECTED;
        btnSend.setEnabled(ready);
        btnSend.setAlpha(ready ? 1f : 0.5f);
        if (session.getStatus() != SessionManager.Status.CONNECTED) {
            tvSendHint.setText(R.string.send_no_session);
        } else if (recipientId == null) {
            tvSendHint.setText(R.string.send_need_recipient);
        } else if (file == null) {
            tvSendHint.setText(R.string.send_need_file);
        } else {
            tvSendHint.setText(getString(R.string.send_ready, file.name, recipientName()));
        }
    }

    private void send() {
        transferId = session.sendFile(file, Integer.parseInt(recipientId));
        if (transferId == -1) {
            Toast.makeText(this, R.string.send_failed_no_session, Toast.LENGTH_LONG).show();
            return;
        }
        tvProgressTitle.setText(getString(R.string.transfer_to, file.name, recipientName()));
        tvProgressPercent.setText("0 %");
        tvProgressState.setText(R.string.transfer_waiting);
        pbSend.setProgress(0);
        btnCancelTransfer.setVisibility(View.VISIBLE);
        cardProgress.setVisibility(View.VISIBLE);
        updateSendButton();
    }

    // ------------------------------------------------------------------
    // Progression (#22, #23)
    // ------------------------------------------------------------------

    @Override
    public void onTransferChanged(Transfer t) {
        if (t.transferId != transferId) return;
        cardProgress.setVisibility(View.VISIBLE);
        tvProgressTitle.setText(getString(R.string.transfer_to, t.fileName, session.participantName(t.peerId)));
        tvProgressPercent.setText(t.percent() + " %");
        pbSend.setProgress(t.percent());
        switch (t.state) {
            case WAITING_ACCEPT:
                tvProgressState.setText(R.string.transfer_waiting);
                break;
            case SENDING:
                tvProgressState.setText(getString(R.string.transfer_sending, t.blocksDone, t.blocksTotal));
                break;
            case VERIFYING:
                tvProgressState.setText(R.string.transfer_verifying);
                break;
            case COMPLETED:
                tvProgressState.setText(R.string.transfer_done);
                break;
            case FAILED:
                tvProgressState.setText(getString(R.string.transfer_failed, t.error));
                break;
            case CANCELLED:
                tvProgressState.setText(R.string.transfer_cancelled);
                break;
        }
        btnCancelTransfer.setVisibility(t.state.isFinished() ? View.GONE : View.VISIBLE);
        updateSendButton();
    }
}
