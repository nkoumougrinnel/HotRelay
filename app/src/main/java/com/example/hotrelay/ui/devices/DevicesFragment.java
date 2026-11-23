package com.example.hotrelay.ui.devices;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.hotrelay.R;
import com.example.hotrelay.session.SessionManager;
import com.example.hotrelay.ui.adapter.ParticipantAdapter;
import com.example.hotrelay.ui.send.SendFileActivity;

/**
 * Onglet Appareils (#18) : liste en direct des participants de la session,
 * hôte compris, que l'on soit hôte ou client.
 */
public class DevicesFragment extends Fragment implements SessionManager.Listener {

    private SessionManager session;
    private ParticipantAdapter adapter;
    private TextView tvSession, tvEmpty;
    private RecyclerView rvDevices;
    private View btnLeave, btnSendFile;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_devices, container, false);
        session = SessionManager.get(requireContext());

        tvSession = root.findViewById(R.id.tvDevicesSession);
        tvEmpty = root.findViewById(R.id.tvDevicesEmpty);
        btnLeave = root.findViewById(R.id.btnLeaveSession);
        btnSendFile = root.findViewById(R.id.btnSendFile);
        rvDevices = root.findViewById(R.id.rvDevices);
        rvDevices.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new ParticipantAdapter();
        rvDevices.setAdapter(adapter);
        // Appui sur un appareil : l'envoyer comme destinataire
        adapter.setOnParticipantClick(p -> openSend(Integer.parseInt(p.getId())));
        btnSendFile.setOnClickListener(v -> openSend(-1));

        btnLeave.setOnClickListener(v -> session.stop());
        return root;
    }

    private void openSend(int recipientId) {
        Intent i = new Intent(requireContext(), SendFileActivity.class);
        if (recipientId >= 0) i.putExtra(SendFileActivity.EXTRA_RECIPIENT_ID, recipientId);
        startActivity(i);
    }

    @Override
    public void onStart() {
        super.onStart();
        session.addListener(this);
    }

    @Override
    public void onStop() {
        session.removeListener(this);
        super.onStop();
    }

    @Override
    public void onSessionChanged(SessionManager s) {
        boolean connected = s.getStatus() == SessionManager.Status.CONNECTED;
        adapter.setParticipants(connected ? s.getParticipants() : java.util.Collections.emptyList());
        rvDevices.setVisibility(connected ? View.VISIBLE : View.GONE);
        tvEmpty.setVisibility(connected ? View.GONE : View.VISIBLE);
        btnLeave.setVisibility(s.isActive() ? View.VISIBLE : View.GONE);
        btnSendFile.setVisibility(connected ? View.VISIBLE : View.GONE);

        if (s.getStatus() == SessionManager.Status.JOINING) {
            tvEmpty.setText(R.string.devices_joining);
            tvSession.setText("");
        } else if (!connected) {
            tvEmpty.setText(R.string.devices_no_session);
            tvSession.setText("");
        } else if (s.getRole() == SessionManager.Role.HOST) {
            tvSession.setText(getString(R.string.devices_session_host, s.getParticipants().size()));
        } else {
            tvSession.setText(getString(R.string.devices_session_client, s.getHostName(), s.getParticipants().size()));
        }
    }
}
