package com.example.hotrelay.ui.host;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.hotrelay.R;
import com.example.hotrelay.protocol.Hrp;
import com.example.hotrelay.session.SessionManager;
import com.example.hotrelay.ui.adapter.ParticipantAdapter;

import java.net.SocketException;

/**
 * Écran de l'hôte (#16) : ouvre la session sur le port 50000, affiche l'IP à donner
 * aux invités et la liste des appareils connectés, en direct.
 *
 * <p>« Retour » laisse la session ouverte ; « Arrêter le relais » la ferme (EXIT à tous).</p>
 */
public class HostSessionActivity extends AppCompatActivity implements SessionManager.Listener {

    private SessionManager session;
    private ParticipantAdapter adapter;
    private TextView tvHostIp, tvHostNotice, tvStatDevices, tvNoClients;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_host_session);
        session = SessionManager.get(this);

        // Une session client est déjà en cours : on ne peut pas être hôte en même temps.
        if (session.getRole() == SessionManager.Role.CLIENT) {
            Toast.makeText(this, R.string.session_busy_client, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        tvHostIp = findViewById(R.id.tvHostIp);
        tvHostNotice = findViewById(R.id.tvHostNotice);
        tvStatDevices = findViewById(R.id.tvStatDevices);
        tvNoClients = findViewById(R.id.tvNoClients);
        TextView tvHostPort = findViewById(R.id.tvHostPort);
        tvHostPort.setText(getString(R.string.udp_port_label, Hrp.PORT));

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnStopSession).setOnClickListener(v -> {
            session.stop();
            finish();
        });

        RecyclerView rv = findViewById(R.id.rvParticipants);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ParticipantAdapter();
        rv.setAdapter(adapter);

        try {
            session.startHost(); // sans effet si déjà ouverte
        } catch (SocketException e) {
            Toast.makeText(this, getString(R.string.host_port_error, Hrp.PORT, e.getMessage()),
                    Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        session.addListener(this);
        showHostIp();
    }

    @Override
    protected void onStop() {
        session.removeListener(this);
        super.onStop();
    }

    /** L'IP peut changer si on active le hotspot après avoir ouvert l'écran : relue à chaque retour. */
    private void showHostIp() {
        String ip = session.getHotspotIp();
        if (ip != null) {
            tvHostIp.setText(ip);
            tvHostNotice.setText(R.string.host_keep_open_notice);
        } else {
            tvHostIp.setText(R.string.host_ip_unknown);
            tvHostNotice.setText(R.string.host_ip_unknown_hint);
        }
    }

    @Override
    public void onSessionChanged(SessionManager s) {
        if (s.getRole() != SessionManager.Role.HOST) return;
        adapter.setParticipants(s.getClients());
        int n = s.getClients().size();
        tvStatDevices.setText(String.valueOf(n));
        tvNoClients.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
    }
}
