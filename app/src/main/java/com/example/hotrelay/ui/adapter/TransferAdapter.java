package com.example.hotrelay.ui.adapter;

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
import com.example.hotrelay.model.TransferItem;
import com.example.hotrelay.model.TransferStatus;

import java.util.ArrayList;
import java.util.List;

public class TransferAdapter extends RecyclerView.Adapter<TransferAdapter.ViewHolder> {

    private List<TransferItem> transfers = new ArrayList<>();

    public void setTransfers(List<TransferItem> newTransfers) {
        this.transfers = newTransfers;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_transfer, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        TransferItem item = transfers.get(position);
        holder.tvFileName.setText(item.getFileName());

        if (item.getStatus() == TransferStatus.SUCCESS) {
            holder.tvStatusText.setText(item.isOutgoing() ? "Transfert réussi" : "Reçu • Terminé");
            holder.tvStatusText.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.status_green));
            holder.ivStatusIcon.setImageResource(R.drawable.ic_check_circle);
            holder.ivStatusIcon.setColorFilter(ContextCompat.getColor(holder.itemView.getContext(), R.color.status_green));
            holder.pbTransfer.setVisibility(View.GONE);
        } else if (item.getStatus() == TransferStatus.FAILED) {
            holder.tvStatusText.setText("Échec • Réessayez");
            holder.tvStatusText.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.status_red));
            holder.ivStatusIcon.setImageResource(R.drawable.ic_error_circle);
            holder.ivStatusIcon.setColorFilter(ContextCompat.getColor(holder.itemView.getContext(), R.color.status_red));
            holder.pbTransfer.setVisibility(View.GONE);
        } else {
            holder.tvStatusText.setText(item.getProgress() + "% • via l'hôte");
            holder.tvStatusText.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.primary));
            holder.pbTransfer.setVisibility(View.VISIBLE);
            holder.pbTransfer.setProgress(item.getProgress());
        }

        // Set Icon based on extension
        if (item.getFileName().endsWith(".zip") || item.getFileName().endsWith(".rar")) {
            holder.ivFileIcon.setImageResource(R.drawable.ic_file_zip);
        } else if (item.getFileName().endsWith(".mp4") || item.getFileName().endsWith(".mkv")) {
            holder.ivFileIcon.setImageResource(R.drawable.ic_file_video);
        } else {
            holder.ivFileIcon.setImageResource(R.drawable.ic_file_document);
        }
    }

    @Override
    public int getItemCount() {
        return transfers.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvFileName, tvStatusText;
        ImageView ivFileIcon, ivStatusIcon;
        ProgressBar pbTransfer;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvFileName = itemView.findViewById(R.id.tvFileName);
            tvStatusText = itemView.findViewById(R.id.tvStatusText);
            ivFileIcon = itemView.findViewById(R.id.ivFileIcon);
            ivStatusIcon = itemView.findViewById(R.id.ivStatusIcon);
            pbTransfer = itemView.findViewById(R.id.pbTransfer);
        }
    }
}