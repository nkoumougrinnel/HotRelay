package com.example.hotrelay.ui.host;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;

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

        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        if (btnStopSession != null) {
            btnStopSession.setOnClickListener(v -> finish());
        }

        if (rvParticipants != null) {
            ParticipantAdapter participantAdapter = new ParticipantAdapter();
            rvParticipants.setLayoutManager(new LinearLayoutManager(this));
            rvParticipants.setAdapter(participantAdapter);

            List<Participant> mockParticipants = new ArrayList<>();
            mockParticipants.add(new Participant("1", "Paul", "192.168.43.102", 5000, true, false));
            mockParticipants.add(new Participant("2", "Aïcha", "192.168.43.87", 5000, true, false));

            participantAdapter.setParticipants(mockParticipants);
        }
    }
}