package com.example.hotrelay.ui.transfers;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.hotrelay.R;
import com.example.hotrelay.files.FileInfo;
import com.example.hotrelay.protocol.Hrp;
import com.example.hotrelay.session.SessionManager;
import com.example.hotrelay.transfer.Transfer;
import com.example.hotrelay.ui.adapter.TransferAdapter;

import java.util.List;

/**
 * Onglet Transferts (#23, #27) : le détail d'un transfert (trajet, avancement, compteurs,
 * grille des paquets) et l'historique de tous les envois et réceptions.
 *
 * <p>Par défaut, le détail montre le transfert en cours le plus récent ; un appui sur
 * une ligne de l'historique l'affiche à la place.</p>
 */
public class TransfersFragment extends Fragment implements SessionManager.Listener {

    private SessionManager session;
    private TransferAdapter adapter;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private View detail, btnCancel, node2, line2;
    private TextView tvEmpty, tvHistoryTitle, tvDetailTitle, tvFile, tvPercent, tvState;
    private TextView tvNode1Name, tvNode1Detail, tvNode2Name, tvNode2Detail, tvNode3Name, tvNode3Detail;
    private TextView tvBlocks, tvBlocksLabel, tvAcks, tvAcksLabel, tvRetries, tvRetriesLabel, tvSpeed, tvSpeedLabel;
    private TextView tvGridTitle, tvGrid, tvLegend;
    private ProgressBar pbDetail;

    /** Transfert affiché en détail ; -1 = choisir automatiquement. */
    private int selectedId = -1;
    private boolean userSelected;

    /** Rafraîchit le débit et le temps restant chaque seconde, même sans nouveau paquet. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            ui.postDelayed(this, 1000);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_transfers, container, false);
        session = SessionManager.get(requireContext());

        tvEmpty = root.findViewById(R.id.tvTransfersEmpty);
        detail = root.findViewById(R.id.detailContainer);
        tvDetailTitle = root.findViewById(R.id.tvDetailTitle);
        tvNode1Name = root.findViewById(R.id.tvNode1Name);
        tvNode1Detail = root.findViewById(R.id.tvNode1Detail);
        tvNode2Name = root.findViewById(R.id.tvNode2Name);
        tvNode2Detail = root.findViewById(R.id.tvNode2Detail);
        tvNode3Name = root.findViewById(R.id.tvNode3Name);
        tvNode3Detail = root.findViewById(R.id.tvNode3Detail);
        node2 = root.findViewById(R.id.node2);
        line2 = root.findViewById(R.id.tvLine2);
        tvFile = root.findViewById(R.id.tvDetailFile);
        tvPercent = root.findViewById(R.id.tvDetailPercent);
        pbDetail = root.findViewById(R.id.pbDetail);
        tvState = root.findViewById(R.id.tvDetailState);
        tvBlocks = root.findViewById(R.id.tvMetricBlocks);
        tvBlocksLabel = root.findViewById(R.id.tvMetricBlocksLabel);
        tvAcks = root.findViewById(R.id.tvMetricAcks);
        tvAcksLabel = root.findViewById(R.id.tvMetricAcksLabel);
        tvRetries = root.findViewById(R.id.tvMetricRetries);
        tvRetriesLabel = root.findViewById(R.id.tvMetricRetriesLabel);
        tvSpeed = root.findViewById(R.id.tvMetricSpeed);
        tvSpeedLabel = root.findViewById(R.id.tvMetricSpeedLabel);
        tvGridTitle = root.findViewById(R.id.tvGridTitle);
        tvGrid = root.findViewById(R.id.tvPacketGrid);
        tvLegend = root.findViewById(R.id.tvGridLegend);
        btnCancel = root.findViewById(R.id.btnCancelSend);
        tvHistoryTitle = root.findViewById(R.id.tvHistoryTitle);

        btnCancel.setOnClickListener(v -> session.cancelTransfer(selectedId));

        RecyclerView rv = root.findViewById(R.id.rvTransfers);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new TransferAdapter(session::participantName);
        adapter.setOnItemClickListener(t -> {
            selectedId = t.transferId;
            userSelected = true;
            render();
        });
        rv.setAdapter(adapter);
        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        session.addListener(this);
        ui.post(tick);
    }

    @Override
    public void onStop() {
        ui.removeCallbacks(tick);
        session.removeListener(this);
        super.onStop();
    }

    @Override
    public void onSessionChanged(SessionManager s) {
        render();
    }

    @Override
    public void onTransferChanged(Transfer t) {
        // Un nouveau transfert démarre : on le suit, sauf si l'utilisateur en regarde un autre en cours.
        Transfer current = session.getTransfer(selectedId);
        boolean currentActive = current != null && !current.state.isFinished();
        if (!t.state.isFinished() && (!userSelected || !currentActive)) {
            selectedId = t.transferId;
        }
        render();
    }

    // ------------------------------------------------------------------

    private void render() {
        if (getView() == null) return;
        List<Transfer> all = session.getTransfers();
        boolean empty = all.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        tvHistoryTitle.setVisibility(empty ? View.GONE : View.VISIBLE);

        Transfer t = session.getTransfer(selectedId);
        if (t == null && !empty) {
            t = firstActiveOr(all);
            selectedId = t.transferId;
        }
        adapter.setTransfers(all, selectedId);
        detail.setVisibility(t == null ? View.GONE : View.VISIBLE);
        if (t != null) showDetail(t);
    }

    private static Transfer firstActiveOr(List<Transfer> all) {
        for (Transfer x : all) if (!x.state.isFinished()) return x;
        return all.get(0); // le plus récent
    }

    private void showDetail(Transfer t) {
        tvDetailTitle.setText(titleFor(t));
        showRoute(t);

        tvFile.setText(t.fileName);
        tvPercent.setText(t.percent() + " %");
        pbDetail.setProgress(t.percent());
        tvState.setText(stateLine(t));

        long now = System.currentTimeMillis();
        if (t.outgoing) {
            tvBlocks.setText(t.blocksDone + " / " + t.blocksTotal);
            tvBlocksLabel.setText(R.string.metric_blocks_sent);
            tvAcks.setText(String.valueOf(t.packets));
            tvAcksLabel.setText(R.string.metric_take_sent);
            tvRetriesLabel.setText(R.string.metric_retries);
            tvLegend.setText(R.string.grid_legend_out);
        } else {
            tvBlocks.setText(t.blocksDone + " / " + t.blocksTotal);
            tvBlocksLabel.setText(R.string.metric_blocks_received);
            tvAcks.setText(String.valueOf(t.packets));
            tvAcksLabel.setText(R.string.metric_take_received);
            tvRetriesLabel.setText(R.string.metric_duplicates);
            tvLegend.setText(R.string.grid_legend_in);
        }
        tvRetries.setText(String.valueOf(t.retransmissions));
        tvSpeed.setText(FileInfo.formatSize(t.bytesPerSecond(now)) + "/s");
        tvSpeedLabel.setText(R.string.metric_speed);

        int cells = t.gridCells();
        long perCell = Math.max(1, (t.blocksTotal + cells - 1) / cells);
        tvGridTitle.setText(perCell > 1
                ? getString(R.string.grid_title_grouped, t.blocksTotal, perCell)
                : getString(R.string.grid_title, t.blocksTotal));
        tvGrid.setText(grid(t));

        btnCancel.setVisibility(t.outgoing && !t.state.isFinished() ? View.VISIBLE : View.GONE);
    }

    /** Trajet : l'expéditeur à gauche, le destinataire à droite, l'hôte au milieu s'il relaie. */
    private void showRoute(Transfer t) {
        boolean iAmHost = session.getRole() == SessionManager.Role.HOST;
        String me = getString(iAmHost ? R.string.you_host : R.string.you);
        String meDetail = getString(R.string.id_label, Math.max(0, session.getMyId()));
        String peer = session.participantName(t.peerId);
        String peerDetail = getString(R.string.id_label, t.peerId);

        // L'hôte n'apparaît au milieu que s'il relaie entre deux clients.
        boolean relayed = !iAmHost && t.peerId != Hrp.HOST_ID;
        node2.setVisibility(relayed ? View.VISIBLE : View.GONE);
        line2.setVisibility(relayed ? View.VISIBLE : View.GONE);
        if (relayed) {
            tvNode2Name.setText(R.string.host);
            String ip = session.getHostAddress();
            tvNode2Detail.setText(ip != null ? ip : session.getHostName());
        }

        if (t.outgoing) {
            tvNode1Name.setText(me);
            tvNode1Detail.setText(meDetail);
            tvNode3Name.setText(peer);
            tvNode3Detail.setText(peerDetail);
        } else {
            tvNode1Name.setText(peer);
            tvNode1Detail.setText(peerDetail);
            tvNode3Name.setText(me);
            tvNode3Detail.setText(meDetail);
        }
    }

    private String titleFor(Transfer t) {
        switch (t.state) {
            case COMPLETED: return getString(t.outgoing ? R.string.detail_sent : R.string.detail_received);
            case FAILED: return getString(R.string.detail_failed);
            case CANCELLED: return getString(R.string.detail_cancelled);
            default: return getString(t.outgoing ? R.string.detail_sending : R.string.detail_receiving);
        }
    }

    private String stateLine(Transfer t) {
        String done = FileInfo.formatSize(t.bytesDone());
        String total = FileInfo.formatSize(t.fileSize);
        switch (t.state) {
            case WAITING_ACCEPT: return getString(R.string.transfer_waiting);
            case VERIFYING: return getString(R.string.transfer_verifying);
            case COMPLETED: return getString(R.string.transfer_done) + " • " + total;
            case FAILED: return getString(R.string.transfer_failed, t.error);
            case CANCELLED: return getString(R.string.transfer_cancelled);
            default:
                long speed = t.bytesPerSecond(System.currentTimeMillis());
                if (speed <= 0) return getString(R.string.bytes_progress, done, total);
                long seconds = (t.fileSize - t.bytesDone()) / speed;
                return getString(R.string.bytes_progress_eta, done, total, duration(seconds));
        }
    }

    private static String duration(long s) {
        if (s < 60) return Math.max(1, s) + " s";
        return (s / 60) + " min " + (s % 60) + " s";
    }

    /**
     * Grille des paquets : chaque case regroupe un ou plusieurs blocs.
     * 🟩 confirmé · 🟧 au moins un renvoi / doublon · 🟦 en cours · ⬜ à venir.
     */
    private static String grid(Transfer t) {
        int cells = t.gridCells();
        if (t.blocksTotal == 0) return "🟩";
        int doneCells = (int) (t.blocksDone * cells / t.blocksTotal); // cases entièrement terminées
        boolean active = !t.state.isFinished();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells; i++) {
            boolean retried = (t.retriedCells & (1L << i)) != 0;
            String cell;
            if (i < doneCells || t.state == Transfer.State.COMPLETED) {
                cell = retried ? "🟧" : "🟩";
            } else if (i == doneCells && active) {
                cell = retried ? "🟧" : "🟦";
            } else {
                cell = "⬜";
            }
            sb.append(cell);
            if ((i + 1) % 15 == 0 && i + 1 < cells) sb.append('\n');
        }
        return sb.toString();
    }
}
