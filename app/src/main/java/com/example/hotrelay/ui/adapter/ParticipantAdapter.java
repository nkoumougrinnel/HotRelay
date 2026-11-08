package com.example.hotrelay.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.hotrelay.R;
import com.example.hotrelay.model.Participant;

import java.util.ArrayList;
import java.util.List;

public class ParticipantAdapter extends RecyclerView.Adapter<ParticipantAdapter.ViewHolder> {

    private List<Participant> participants = new ArrayList<>();

    public void setParticipants(List<Participant> newParticipants) {
        this.participants = newParticipants;
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
        Participant participant = participants.get(position);
        holder.tvName.setText(participant.getName());
        holder.tvStatus.setText(participant.isConnected() ? "Connecté(e)" : "Déconnecté(e)");
        holder.vStatusDot.setVisibility(participant.isConnected() ? View.VISIBLE : View.GONE);
    }

    @Override
    public int getItemCount() {
        return participants.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvName, tvStatus;
        View vStatusDot;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tvParticipantName);
            tvStatus = itemView.findViewById(R.id.tvParticipantStatus);
            vStatusDot = itemView.findViewById(R.id.vStatusDot);
        }
    }
}