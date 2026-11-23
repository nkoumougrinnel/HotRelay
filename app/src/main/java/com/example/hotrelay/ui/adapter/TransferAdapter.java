package com.example.hotrelay.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.hotrelay.R;
import com.example.hotrelay.files.FileInfo;
import com.example.hotrelay.transfer.Transfer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Historique des transferts (onglet Transferts, #27). */
public class TransferAdapter extends RecyclerView.Adapter<TransferAdapter.ViewHolder> {

    public interface OnItemClickListener {
        void onItemClick(Transfer transfer);
    }

    /** Donne le nom d'un participant à partir de son ID. */
    public interface NameResolver {
        String nameOf(int participantId);
    }

    private List<Transfer> transfers = new ArrayList<>();
    private OnItemClickListener onItemClickListener;
    private final NameResolver names;
    private int selectedId = -1;

    public TransferAdapter(NameResolver names) {
        this.names = names;
    }

    public void setTransfers(List<Transfer> newTransfers, int selectedTransferId) {
        this.transfers = new ArrayList<>(newTransfers);
        this.selectedId = selectedTransferId;
        notifyDataSetChanged();
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.onItemClickListener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_transfer, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        Transfer t = transfers.get(position);
        Context c = h.itemView.getContext();
        h.tvFileName.setText(t.fileName);
        String peer = names.nameOf(t.peerId);
        String size = FileInfo.formatSize(t.fileSize);
        h.tvMeta.setText(c.getString(t.outgoing ? R.string.item_sent_to : R.string.item_received_from, peer, size));

        int color;
        switch (t.state) {
            case COMPLETED:
                h.tvStatusText.setText(t.outgoing ? R.string.item_done_out : R.string.item_done_in);
                h.ivStatusIcon.setImageResource(R.drawable.ic_check_circle);
                color = R.color.status_green;
                break;
            case FAILED:
                h.tvStatusText.setText(c.getString(R.string.item_failed, t.error));
                h.ivStatusIcon.setImageResource(R.drawable.ic_error_circle);
                color = R.color.status_red;
                break;
            case CANCELLED:
                h.tvStatusText.setText(R.string.item_cancelled);
                h.ivStatusIcon.setImageResource(R.drawable.ic_error_circle);
                color = R.color.text_secondary;
                break;
            case WAITING_ACCEPT:
                h.tvStatusText.setText(R.string.item_waiting);
                h.ivStatusIcon.setImageResource(R.drawable.ic_send);
                color = R.color.primary;
                break;
            case VERIFYING:
                h.tvStatusText.setText(R.string.item_verifying);
                h.ivStatusIcon.setImageResource(R.drawable.ic_send);
                color = R.color.primary;
                break;
            default:
                h.tvStatusText.setText(c.getString(R.string.item_in_progress, t.percent()));
                h.ivStatusIcon.setImageResource(R.drawable.ic_send);
                color = R.color.primary;
        }
        int col = ContextCompat.getColor(c, color);
        h.tvStatusText.setTextColor(col);
        h.ivStatusIcon.setColorFilter(col);

        boolean active = !t.state.isFinished();
        h.pbTransfer.setVisibility(active ? View.VISIBLE : View.GONE);
        h.pbTransfer.setProgress(t.percent());

        h.ivFileIcon.setImageResource(iconFor(t.fileName));
        h.itemView.setAlpha(t.transferId == selectedId ? 1f : 0.85f);
        h.itemView.setOnClickListener(v -> {
            if (onItemClickListener != null) onItemClickListener.onItemClick(t);
        });
    }

    private static int iconFor(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z")) return R.drawable.ic_file_zip;
        if (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi") || n.endsWith(".mov")) {
            return R.drawable.ic_file_video;
        }
        return R.drawable.ic_file_document;
    }

    @Override
    public int getItemCount() {
        return transfers.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvFileName, tvStatusText, tvMeta;
        ImageView ivFileIcon, ivStatusIcon;
        ProgressBar pbTransfer;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvFileName = itemView.findViewById(R.id.tvFileName);
            tvStatusText = itemView.findViewById(R.id.tvStatusText);
            tvMeta = itemView.findViewById(R.id.tvTransferMeta);
            ivFileIcon = itemView.findViewById(R.id.ivFileIcon);
            ivStatusIcon = itemView.findViewById(R.id.ivStatusIcon);
            pbTransfer = itemView.findViewById(R.id.pbTransfer);
        }
    }
}
