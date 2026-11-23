package com.example.hotrelay.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.hotrelay.R;
import com.example.hotrelay.model.Participant;

import java.util.ArrayList;
import java.util.List;

/** Liste des participants d'une session (écran hôte et onglet Appareils). */
public class ParticipantAdapter extends RecyclerView.Adapter<ParticipantAdapter.ViewHolder> {

    private List<Participant> participants = new ArrayList<>();

    public void setParticipants(List<Participant> newParticipants) {
        this.participants = new ArrayList<>(newParticipants);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_participant, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Participant p = participants.get(position);
        holder.tvName.setText(p.isMe() ? p.getName() + " (vous)" : p.getName());

        // Les clients ne connaissent pas l'IP des autres : on affiche alors l'ID.
        String detail = "ID " + p.getId();
        if (p.getIpAddress() != null) detail += " · " + p.getIpAddress();
        holder.tvIp.setText(detail);

        int color;
        if (p.isHost()) {
            holder.tvStatus.setText(R.string.status_host);
            color = R.color.primary;
        } else if (p.isConnected()) {
            holder.tvStatus.setText(R.string.status_connected);
            color = R.color.status_green;
        } else {
            holder.tvStatus.setText(R.string.status_disconnected);
            color = R.color.text_secondary;
        }
        holder.tvStatus.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), color));
    }

    @Override
    public int getItemCount() {
        return participants.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvName, tvIp, tvStatus;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tvParticipantName);
            tvIp = itemView.findViewById(R.id.tvParticipantIp);
            tvStatus = itemView.findViewById(R.id.tvParticipantStatus);
        }
    }
}
