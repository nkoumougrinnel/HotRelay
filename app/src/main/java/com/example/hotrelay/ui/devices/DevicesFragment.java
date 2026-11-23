package com.example.hotrelay.ui.devices;

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

/**
 * Onglet Appareils (#18) : liste en direct des participants de la session,
 * hôte compris, que l'on soit hôte ou client.
 */
public class DevicesFragment extends Fragment implements SessionManager.Listener {

    private SessionManager session;
    private ParticipantAdapter adapter;
    private TextView tvSession, tvEmpty;
    private RecyclerView rvDevices;
    private View btnLeave;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_devices, container, false);
        session = SessionManager.get(requireContext());

        tvSession = root.findViewById(R.id.tvDevicesSession);
        tvEmpty = root.findViewById(R.id.tvDevicesEmpty);
        btnLeave = root.findViewById(R.id.btnLeaveSession);
        rvDevices = root.findViewById(R.id.rvDevices);
        rvDevices.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new ParticipantAdapter();
        rvDevices.setAdapter(adapter);

        btnLeave.setOnClickListener(v -> session.stop());
        return root;
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
