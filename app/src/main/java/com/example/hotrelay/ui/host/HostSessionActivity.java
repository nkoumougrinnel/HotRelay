package com.example.hotrelay.ui.host;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.hotrelay.R;
import com.example.hotrelay.model.Participant;
import com.example.hotrelay.ui.adapter.ParticipantAdapter;

import java.util.ArrayList;
import java.util.List;

public class HostSessionActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_host_session);

        ImageView btnBack = findViewById(R.id.btnBack);
        View btnStopSession = findViewById(R.id.btnStopSession);
        RecyclerView rvParticipants = findViewById(R.id.rvParticipants);
        TextView tvParticipantsCount = findViewById(R.id.tvParticipantsCount);

        btnBack.setOnClickListener(v -> finish());

        btnStopSession.setOnClickListener(v -> {
            Toast.makeText(this, "Session arrêtée", Toast.LENGTH_SHORT).show();
            finish();
        });

        ParticipantAdapter participantAdapter = new ParticipantAdapter();
        rvParticipants.setLayoutManager(new LinearLayoutManager(this));
        rvParticipants.setAdapter(participantAdapter);

        // Sample mock data for previewing UI (Matching mockup)
        List<Participant> mockParticipants = new ArrayList<>();
        mockParticipants.add(new Participant("1", "Maya", "192.168.43.10", 8888, true, false));
        mockParticipants.add(new Participant("2", "Noah", "192.168.43.11", 8888, true, false));
        mockParticipants.add(new Participant("3", "Lina", "192.168.43.12", 8888, true, false));

        participantAdapter.setParticipants(mockParticipants);
        tvParticipantsCount.setText(mockParticipants.size() + " participants");
    }
}