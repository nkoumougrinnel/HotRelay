package com.example.hotrelay.session;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.example.hotrelay.model.Participant;
import com.example.hotrelay.network.NetworkUtils;
import com.example.hotrelay.protocol.Hrp;
import com.example.hotrelay.protocol.Messages;

import java.net.InetAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Point d'entrée unique de l'interface vers le réseau (#16, #18).
 *
 * <p>Une seule session à la fois dans l'application : hôte OU client. Elle survit aux
 * changements d'écran. Les écrans s'abonnent avec {@link #addListener} dans
 * {@code onStart} et se désabonnent dans {@code onStop}.</p>
 *
 * <p>Tout ce qui sort d'ici arrive sur le <b>thread UI</b> : les écrans peuvent
 * toucher aux vues directement. Les getters sont à appeler depuis le thread UI.</p>
 */
public final class SessionManager {

    public enum Role { NONE, HOST, CLIENT }

    public enum Status { IDLE, JOINING, CONNECTED }

    /** Abonnement des écrans. Toutes les méthodes sont appelées sur le thread UI. */
    public interface Listener {
        /** Rôle, statut ou liste des participants ont changé. */
        void onSessionChanged(SessionManager session);

        /** Le client vient d'être admis (JOIN reçu). */
        default void onJoined(int myId, String hostName) {}

        /** La tentative de connexion a échoué (hôte injoignable, session pleine…). */
        default void onJoinFailed(String reason) {}

        /** La session s'est terminée sans que l'utilisateur l'ait demandé (EXIT, hôte perdu). */
        default void onSessionEnded(String reason) {}
    }

    private static final String PREFS = "hotrelay";
    private static final String KEY_NAME = "device_name";

    private static SessionManager instance;

    public static synchronized SessionManager get(Context context) {
        if (instance == null) instance = new SessionManager(context.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private Role role = Role.NONE;
    private Status status = Status.IDLE;
    private HostSession host;
    private ClientSession client;
    private List<Participant> participants = Collections.emptyList();
    private int myId = -1;
    private String hostName = "";
    private InetAddress hostIp;

    private SessionManager(Context app) {
        this.app = app;
    }

    // ------------------------------------------------------------------
    // Abonnements
    // ------------------------------------------------------------------

    public void addListener(Listener l) {
        listeners.add(l);
        l.onSessionChanged(this);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    // ------------------------------------------------------------------
    // Nom de l'appareil (Paramètres)
    // ------------------------------------------------------------------

    public String getDeviceName() {
        String n = prefs().getString(KEY_NAME, null);
        return n == null || n.trim().isEmpty() ? Build.MODEL : n;
    }

    public void setDeviceName(String name) {
        prefs().edit().putString(KEY_NAME, name == null ? "" : name.trim()).apply();
    }

    private SharedPreferences prefs() {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------
    // Hôte
    // ------------------------------------------------------------------

    /** Ouvre la session hôte sur le port 50000. Sans effet si elle est déjà ouverte. */
    public void startHost() throws SocketException {
        if (role == Role.HOST) return;
        if (role != Role.NONE) throw new IllegalStateException("une session client est en cours");
        HostSession h = new HostSession(getDeviceName(), new HostSession.Listener() {
            @Override
            public void onParticipantsChanged(List<Messages.Participant> list) {
                main.post(() -> onHostParticipants(list));
            }
        });
        h.start();
        host = h;
        role = Role.HOST;
        status = Status.CONNECTED;
        myId = Hrp.HOST_ID;
        hostName = getDeviceName();
        notifyChanged();
    }

    private void onHostParticipants(List<Messages.Participant> list) {
        if (role != Role.HOST || host == null) return;
        List<Participant> out = new ArrayList<>();
        for (Messages.Participant p : list) {
            String ip = null;
            int port = 0;
            ParticipantRegistry.Entry e = host.getRegistry().get(p.id);
            if (e != null) {
                ip = e.address.getHostAddress();
                port = e.port;
            }
            out.add(toModel(p, ip, port));
        }
        participants = out;
        notifyChanged();
    }

    /** IP à afficher aux invités (interface du hotspot), ou {@code null}. */
    public String getHotspotIp() {
        InetAddress a = NetworkUtils.getHotspotIp();
        return a == null ? null : a.getHostAddress();
    }

    // ------------------------------------------------------------------
    // Client
    // ------------------------------------------------------------------

    /** Envoie NEW à l'hôte. Le résultat arrive par onJoined / onJoinFailed. */
    public void join(InetAddress hostAddress, String name) throws SocketException {
        if (role != Role.NONE) throw new IllegalStateException("une session est déjà en cours");
        setDeviceName(name);
        ClientSession c = new ClientSession(getDeviceName(), hostAddress, new ClientSession.Listener() {
            @Override
            public void onJoined(int id, String hName) {
                main.post(() -> onClientJoined(id, hName));
            }

            @Override
            public void onParticipantsChanged(List<Messages.Participant> list) {
                main.post(() -> onClientParticipants(list));
            }

            @Override
            public void onJoinFailed(String reason) {
                main.post(() -> onClientJoinFailed(reason));
            }

            @Override
            public void onDisconnected(String reason) {
                main.post(() -> onClientDisconnected(reason));
            }
        });
        c.start();
        client = c;
        hostIp = hostAddress;
        role = Role.CLIENT;
        status = Status.JOINING;
        notifyChanged();
    }

    /** IP de la passerelle Wi-Fi = IP probable de l'hôte, pour pré-remplir le champ (#13). */
    public String guessHostIp() {
        InetAddress a = NetworkUtils.getGatewayIp(app);
        return a == null ? null : a.getHostAddress();
    }

    private void onClientJoined(int id, String hName) {
        if (role != Role.CLIENT) return;
        myId = id;
        hostName = hName;
        status = Status.CONNECTED;
        notifyChanged();
        for (Listener l : listeners) l.onJoined(id, hName);
    }

    private void onClientParticipants(List<Messages.Participant> list) {
        if (role != Role.CLIENT) return;
        List<Participant> out = new ArrayList<>();
        for (Messages.Participant p : list) {
            // Les clients ne connaissent que l'IP de l'hôte (§7 : IP et ports jamais diffusés).
            String ip = p.id == Hrp.HOST_ID && hostIp != null ? hostIp.getHostAddress() : null;
            out.add(toModel(p, ip, p.id == Hrp.HOST_ID ? Hrp.PORT : 0));
        }
        participants = out;
        notifyChanged();
    }

    private void onClientJoinFailed(String reason) {
        reset();
        for (Listener l : listeners) l.onJoinFailed(reason);
    }

    private void onClientDisconnected(String reason) {
        reset();
        for (Listener l : listeners) l.onSessionEnded(reason);
    }

    // ------------------------------------------------------------------
    // Fin de session
    // ------------------------------------------------------------------

    /** Arrête la session en cours (hôte : EXIT ×3 ; client : BYE). */
    public void stop() {
        if (host != null) host.stop();
        if (client != null) client.leave();
        reset();
    }

    private void reset() {
        host = null;
        client = null;
        role = Role.NONE;
        status = Status.IDLE;
        participants = Collections.emptyList();
        myId = -1;
        hostName = "";
        hostIp = null;
        notifyChanged();
    }

    // ------------------------------------------------------------------
    // Lecture de l'état (thread UI)
    // ------------------------------------------------------------------

    public Role getRole() { return role; }

    public Status getStatus() { return status; }

    public boolean isActive() { return role != Role.NONE; }

    /** Tous les participants, hôte compris (ID 0 en premier). */
    public List<Participant> getParticipants() { return participants; }

    /** Participants sauf l'hôte. */
    public List<Participant> getClients() {
        List<Participant> out = new ArrayList<>();
        for (Participant p : participants) if (!p.isHost()) out.add(p);
        return out;
    }

    public int getMyId() { return myId; }

    public String getHostName() { return hostName; }

    /** Pour la couche transfert (bloc 2). */
    public HostSession getHostSession() { return host; }

    /** Pour la couche transfert (bloc 2). */
    public ClientSession getClientSession() { return client; }

    // ------------------------------------------------------------------

    private Participant toModel(Messages.Participant p, String ip, int port) {
        Participant m = new Participant(String.valueOf(p.id), p.name, ip, port, true, p.id == Hrp.HOST_ID);
        m.setMe(p.id == myId);
        return m;
    }

    private void notifyChanged() {
        for (Listener l : listeners) l.onSessionChanged(this);
    }
}
