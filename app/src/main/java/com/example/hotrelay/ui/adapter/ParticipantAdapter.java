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
        holder.tvIp.setText(participant.getIpAddress());

        if (position == 0) {
            holder.tvStatus.setText("Envoie...");
            holder.tvStatus.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.primary));
        } else {
            holder.tvStatus.setText("Connectée");
            holder.tvStatus.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.status_green));
        }
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