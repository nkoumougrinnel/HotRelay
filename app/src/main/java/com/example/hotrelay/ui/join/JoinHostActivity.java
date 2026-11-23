package com.example.hotrelay.ui.join;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.hotrelay.MainActivity;
import com.example.hotrelay.R;
import com.example.hotrelay.network.NetworkUtils;
import com.example.hotrelay.session.SessionManager;

import java.net.InetAddress;
import java.net.SocketException;

/**
 * Rejoindre un hôte (#15 côté interface) : IP pré-remplie avec la passerelle Wi-Fi (#13),
 * envoi de NEW, attente du JOIN, puis ouverture de l'onglet Appareils.
 */
public class JoinHostActivity extends AppCompatActivity implements SessionManager.Listener {

    private SessionManager session;
    private EditText etHostIp, etUserName;
    private TextView tvJoinStatus, tvConnectLabel;
    private View btnConnect;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_join_host);
        session = SessionManager.get(this);

        if (session.getRole() == SessionManager.Role.HOST) {
            Toast.makeText(this, R.string.session_busy_host, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (session.getStatus() == SessionManager.Status.CONNECTED) {
            openDevices(); // déjà connecté : rien à saisir
            return;
        }

        etHostIp = findViewById(R.id.etHostIp);
        etUserName = findViewById(R.id.etUserName);
        tvJoinStatus = findViewById(R.id.tvJoinStatus);
        tvConnectLabel = findViewById(R.id.tvConnectLabel);
        btnConnect = findViewById(R.id.btnConnect);
        TextView tvHostIpHint = findViewById(R.id.tvHostIpHint);

        String guess = session.guessHostIp();
        if (guess != null) {
            etHostIp.setText(guess);
            tvHostIpHint.setText(R.string.host_ip_detected);
        } else {
            tvHostIpHint.setText(R.string.host_ip_not_detected);
        }
        etUserName.setText(session.getDeviceName());

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnConnect.setOnClickListener(v -> connect());
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (session != null) session.addListener(this);
    }

    @Override
    protected void onStop() {
        if (session != null) session.removeListener(this);
        super.onStop();
    }

    private void connect() {
        InetAddress ip = NetworkUtils.parseIpv4(etHostIp.getText().toString());
        if (ip == null) {
            showStatus(getString(R.string.invalid_ip));
            return;
        }
        String name = etUserName.getText().toString().trim();
        if (name.isEmpty()) {
            showStatus(getString(R.string.name_required));
            return;
        }
        try {
            session.join(ip, name);
        } catch (SocketException e) {
            showStatus(e.getMessage());
        }
    }

    @Override
    public void onSessionChanged(SessionManager s) {
        boolean joining = s.getStatus() == SessionManager.Status.JOINING;
        btnConnect.setEnabled(!joining);
        btnConnect.setAlpha(joining ? 0.6f : 1f);
        tvConnectLabel.setText(joining ? R.string.connecting : R.string.connect);
        if (joining) showStatus(getString(R.string.connecting));
    }

    @Override
    public void onJoined(int myId, String hostName) {
        Toast.makeText(this, getString(R.string.joined_as, hostName, myId), Toast.LENGTH_SHORT).show();
        openDevices();
    }

    @Override
    public void onJoinFailed(String reason) {
        showStatus(reason);
    }

    private void showStatus(String text) {
        tvJoinStatus.setText(text);
        tvJoinStatus.setVisibility(View.VISIBLE);
    }

    private void openDevices() {
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra(MainActivity.EXTRA_TAB, R.id.navigation_devices);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }
}
