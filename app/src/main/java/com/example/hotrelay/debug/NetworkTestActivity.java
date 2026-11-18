package com.example.hotrelay.debug;

import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.hotrelay.R;
import com.example.hotrelay.network.NetworkUtils;
import com.example.hotrelay.network.UdpTransport;
import com.example.hotrelay.protocol.Hrp;
import com.example.hotrelay.protocol.Messages;
import com.example.hotrelay.session.ClientSession;
import com.example.hotrelay.session.HostSession;

import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Écran TEMPORAIRE de test réseau (#10 PING/PONG, #13 IP de l'hôte).
 *
 * <p>Téléphone A (hotspot) : « Hôte (écoute) » → écoute le port 50000, répond PONG à chaque PING.<br>
 * Téléphone B (connecté au hotspot) : l'IP de l'hôte est pré-remplie, « Client : PING ».</p>
 *
 * <p>Session (#15, #17) : A « Ouvrir session », B et C « Rejoindre ». Chaque téléphone affiche
 * la liste des participants. Couper le Wi-Fi d'un client : il disparaît en moins de 20 s.</p>
 *
 * <p>Logcat : filtrer sur le tag {@code NetTest}. À supprimer avant la démo.</p>
 */
public class NetworkTestActivity extends AppCompatActivity {

    private static final String TAG = "NetTest";

    private TextView logView;
    private EditText hostIpField;
    private UdpTransport transport;
    private boolean hostMode;
    private TextView participantsView;
    private EditText nameField;
    private HostSession hostSession;
    private ClientSession clientSession;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_network_test);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.net_test_root), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left + 32, bars.top + 32, bars.right + 32, bars.bottom + 32);
            return insets;
        });

        logView = findViewById(R.id.net_test_log);
        hostIpField = findViewById(R.id.net_test_host_ip);
        TextView info = findViewById(R.id.net_test_info);
        Button hostBtn = findViewById(R.id.net_test_btn_host);
        Button pingBtn = findViewById(R.id.net_test_btn_ping);

        InetAddress gateway = NetworkUtils.getGatewayIp(this);
        InetAddress hotspot = NetworkUtils.getHotspotIp();
        info.setText("Passerelle Wi-Fi (IP hôte vue du client) : " + ip(gateway)
                + "\nIP hotspot de ce téléphone (si hôte) : " + ip(hotspot));
        if (gateway != null) hostIpField.setText(gateway.getHostAddress());

        hostBtn.setOnClickListener(v -> startHost());
        pingBtn.setOnClickListener(v -> sendPing());

        participantsView = findViewById(R.id.net_test_participants);
        nameField = findViewById(R.id.net_test_name);
        nameField.setText(android.os.Build.MODEL);
        findViewById(R.id.net_test_btn_session_host).setOnClickListener(v -> openSession());
        findViewById(R.id.net_test_btn_session_join).setOnClickListener(v -> joinSession());
        findViewById(R.id.net_test_btn_session_stop).setOnClickListener(v -> stopSession());
    }

    // ---- Session (#15, #17) ----

    private boolean busy() {
        if (transport != null || hostSession != null || clientSession != null) {
            log("déjà en cours : « Quitter » ou relancer l'écran");
            return true;
        }
        return false;
    }

    private void openSession() {
        if (busy()) return;
        hostSession = new HostSession(nameField.getText().toString(), new HostSession.Listener() {
            @Override
            public void onParticipantsChanged(List<Messages.Participant> p) {
                showParticipants("Hôte", p);
            }
        });
        try {
            hostSession.start();
            log("session ouverte, IP à donner aux clients : " + ip(NetworkUtils.getHotspotIp()));
        } catch (SocketException e) {
            log("impossible d'ouvrir la session : " + e.getMessage());
            hostSession = null;
        }
    }

    private void joinSession() {
        if (busy()) return;
        InetAddress host = NetworkUtils.parseIpv4(hostIpField.getText().toString());
        if (host == null) {
            log("IP de l'hôte invalide");
            return;
        }
        clientSession = new ClientSession(nameField.getText().toString(), host, new ClientSession.Listener() {
            @Override public void onJoined(int myId, String hostName) {
                log("JOIN reçu : mon ID = " + myId + ", hôte « " + hostName + " »");
            }
            @Override public void onParticipantsChanged(List<Messages.Participant> p) {
                showParticipants("Client ID " + clientSession.getMyId(), p);
            }
            @Override public void onJoinFailed(String reason) {
                log("échec : " + reason);
                clientSession = null;
            }
            @Override public void onDisconnected(String reason) {
                log("déconnecté : " + reason);
                showParticipants("—", java.util.Collections.emptyList());
                clientSession = null;
            }
        });
        try {
            clientSession.start();
            log("NEW envoyé à " + host.getHostAddress());
        } catch (SocketException e) {
            log("socket client : " + e.getMessage());
            clientSession = null;
        }
    }

    private void stopSession() {
        if (hostSession != null) hostSession.stop();
        if (clientSession != null) clientSession.leave();
        if (transport != null) transport.close();
        hostSession = null;
        clientSession = null;
        transport = null;
        showParticipants("—", java.util.Collections.emptyList());
        log("arrêté");
    }

    private void showParticipants(String role, List<Messages.Participant> p) {
        StringBuilder sb = new StringBuilder(role).append(" · ").append(p.size()).append(" participant(s)\n");
        for (Messages.Participant x : p) sb.append("  ").append(x.id).append(" · ").append(x.name).append('\n');
        log("liste : " + p);
        runOnUiThread(() -> participantsView.setText(sb.toString()));
    }

    private void startHost() {
        if (busy()) return;
        hostMode = true;
        transport = new UdpTransport(Hrp.PORT);
        try {
            transport.start(this::onPacket);
            log("hôte : écoute UDP sur le port " + transport.getLocalPort());
        } catch (SocketException e) {
            log("impossible d'ouvrir le port " + Hrp.PORT + " : " + e.getMessage());
            transport = null;
        }
    }

    private void sendPing() {
        InetAddress host = NetworkUtils.parseIpv4(hostIpField.getText().toString());
        if (host == null) {
            log("IP de l'hôte invalide");
            return;
        }
        if (transport == null) {
            if (hostSession != null || clientSession != null) {
                log("une session est ouverte : « Quitter » d'abord");
                return;
            }
            hostMode = false;
            transport = new UdpTransport(0);
            try {
                transport.start(this::onPacket);
            } catch (SocketException e) {
                log("socket client : " + e.getMessage());
                transport = null;
                return;
            }
        }
        transport.send("PING".getBytes(StandardCharsets.UTF_8), host, Hrp.PORT);
        log("→ PING envoyé à " + host.getHostAddress() + ":" + Hrp.PORT
                + " (port local " + transport.getLocalPort() + ")");
    }

    /** Appelé sur le thread réseau. */
    private void onPacket(byte[] data, InetAddress from, int port) {
        String text = new String(data, StandardCharsets.UTF_8);
        log("← " + text + " de " + from.getHostAddress() + ":" + port);
        if (hostMode && "PING".equals(text)) {
            transport.send("PONG".getBytes(StandardCharsets.UTF_8), from, port);
            log("→ PONG renvoyé à " + from.getHostAddress() + ":" + port);
        }
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.FRANCE).format(new Date()) + "  " + msg + "\n";
        runOnUiThread(() -> logView.append(line));
    }

    private static String ip(InetAddress a) {
        return a == null ? "introuvable" : a.getHostAddress();
    }

    @Override
    protected void onDestroy() {
        stopSession();
        super.onDestroy();
    }
}
