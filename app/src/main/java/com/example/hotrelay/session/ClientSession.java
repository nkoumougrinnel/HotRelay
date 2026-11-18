package com.example.hotrelay.session;

import android.util.Log;

import com.example.hotrelay.network.UdpTransport;
import com.example.hotrelay.protocol.Hrp;
import com.example.hotrelay.protocol.HrpPacket;
import com.example.hotrelay.protocol.Messages;

import java.net.InetAddress;
import java.net.SocketException;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Session côté client (#15, #17) — protocole §8 et §10.
 *
 * <ul>
 *   <li>Envoie NEW avec un REQUEST_ID aléatoire, renvoyé (500 ms, ×2, plafond 4 s, 5 essais)
 *       tant que JOIN n'est pas reçu ;</li>
 *   <li>une fois connecté : AVAILABLE toutes les 5 s, copie de la liste reçue par SYNCTABLE ;</li>
 *   <li>15 s sans aucun datagramme de l'hôte, ou EXIT ⇒ déconnecté ;</li>
 *   <li>{@link #leave()} envoie BYE puis ferme.</li>
 * </ul>
 *
 * <p>Les callbacks du {@link Listener} arrivent sur le thread {@code hrp-client} :
 * utiliser {@code runOnUiThread} pour toucher aux vues.</p>
 */
public final class ClientSession {

    private static final String TAG = "ClientSession";

    public enum State { IDLE, JOINING, CONNECTED, CLOSED }

    public interface Listener {
        /** JOIN reçu : on fait partie de la session. */
        void onJoined(int myId, String hostName);

        /** Nouvelle liste des participants (hôte compris). */
        void onParticipantsChanged(List<Messages.Participant> participants);

        /** Impossible de rejoindre (hôte injoignable, session pleine). */
        void onJoinFailed(String reason);

        /** Session terminée après avoir été rejointe (EXIT, hôte perdu). */
        void onDisconnected(String reason);

        /** Message de transfert reçu de l'hôte (READY, TAKE, NICE, ERROR…) — pour #22 / #25. */
        default void onTransferPacket(HrpPacket packet) {}
    }

    private final String name;
    private final InetAddress hostIp;
    private final int hostPort;
    private final Listener listener;

    // Modifiables par les tests (valeurs du protocole par défaut)
    long heartbeatMs = Hrp.HEARTBEAT_MS;
    long timeoutMs = Hrp.PEER_TIMEOUT_MS;

    private UdpTransport transport;
    private ScheduledExecutorService loop;
    private ScheduledFuture<?> retry;

    private volatile State state = State.IDLE;
    private volatile int myId = -1;
    private volatile String hostName = "";
    private volatile List<Messages.Participant> participants = Collections.emptyList();
    private long requestId;
    private volatile long lastHostPacket;

    public ClientSession(String name, InetAddress hostIp, Listener listener) {
        this(name, hostIp, Hrp.PORT, listener);
    }

    ClientSession(String name, InetAddress hostIp, int hostPort, Listener listener) {
        this.name = name;
        this.hostIp = hostIp;
        this.hostPort = hostPort;
        this.listener = listener;
    }

    /** Ouvre un socket (port libre) et commence à envoyer NEW. */
    public synchronized void start() throws SocketException {
        if (state != State.IDLE) throw new IllegalStateException("déjà démarrée");
        loop = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "hrp-client"));
        transport = new UdpTransport(0);
        try {
            transport.start((data, from, port) -> post(() -> onPacket(data, from, port)));
        } catch (SocketException e) {
            loop.shutdownNow();
            throw e;
        }
        requestId = new SecureRandom().nextLong();
        state = State.JOINING;
        post(() -> sendNew(0));
    }

    /** Quitte la session : BYE puis fermeture. */
    public void leave() {
        post(() -> {
            if (state == State.CONNECTED) send(Messages.bye());
            shutdown();
        });
    }

    public State getState() { return state; }

    /** ID attribué par l'hôte, -1 tant que JOIN n'est pas reçu. */
    public int getMyId() { return myId; }

    public String getHostName() { return hostName; }

    public List<Messages.Participant> getParticipants() { return participants; }

    /** Envoie un paquet à l'hôte (utilisé par la couche transfert). */
    public void send(HrpPacket packet) {
        UdpTransport t = transport;
        if (t != null && state != State.CLOSED) t.send(packet.encode(), hostIp, hostPort);
    }

    /** Exécute une tâche sur le thread de la session. */
    public void post(Runnable r) {
        ScheduledExecutorService l = loop;
        if (l == null) return;
        try {
            l.execute(r);
        } catch (RejectedExecutionException ignored) {
            // session fermée
        }
    }

    // ------------------------------------------------------------------
    // Entrée dans la session : NEW → JOIN
    // ------------------------------------------------------------------

    /** Envoi initial (attempt = 0) puis renvois 1 à 5 : 0 s · 0,5 · 1,5 · 3,5 · 7,5 · 11,5 · échec à 15,5 s. */
    private void sendNew(int attempt) {
        if (state != State.JOINING) return;
        if (attempt > Hrp.MAX_RETRIES) {
            fail("L'hôte ne répond pas (" + hostIp.getHostAddress() + ")");
            return;
        }
        Log.d(TAG, "NEW essai " + attempt + " → " + hostIp.getHostAddress());
        send(new Messages.New(requestId, name).toPacket());
        retry = loop.schedule(() -> sendNew(attempt + 1), Hrp.retryDelayMs(attempt + 1), TimeUnit.MILLISECONDS);
    }

    private void onJoined(Messages.Join j) {
        if (state != State.JOINING || j.requestId != requestId) return; // doublon ou réponse périmée
        if (retry != null) retry.cancel(false);
        myId = j.id;
        hostName = j.hostName;
        state = State.CONNECTED;
        Log.i(TAG, "connecté : ID " + myId + " chez « " + hostName + " »");
        loop.scheduleWithFixedDelay(() -> send(Messages.available()),
                heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
        long check = Math.max(50, Math.min(1000, timeoutMs / 4));
        loop.scheduleWithFixedDelay(this::checkHost, check, check, TimeUnit.MILLISECONDS);
        listener.onJoined(myId, hostName);
    }

    // ------------------------------------------------------------------
    // Réception
    // ------------------------------------------------------------------

    private void onPacket(byte[] data, InetAddress from, int port) {
        if (state == State.CLOSED) return;
        // On n'écoute que l'hôte : les clients ne se parlent jamais directement (§2).
        if (port != hostPort || !from.equals(hostIp)) return;
        HrpPacket p = HrpPacket.decode(data);
        if (p == null) return;
        lastHostPacket = System.currentTimeMillis();

        try {
            switch (p.type) {
                case Hrp.JOIN:
                    onJoined(Messages.Join.parse(p));
                    break;
                case Hrp.SYNCTABLE:
                    if (state == State.CONNECTED) {
                        participants = Messages.SyncTable.parse(p).participants;
                        listener.onParticipantsChanged(participants);
                    }
                    break;
                case Hrp.EXIT:
                    disconnect("L'hôte a fermé la session");
                    break;
                case Hrp.ERROR:
                    Messages.ErrorMessage err = Messages.ErrorMessage.parse(p);
                    if (state == State.JOINING && err.code == Hrp.ERR_SESSION_FULL) {
                        fail("Session pleine");
                    } else if (state == State.CONNECTED) {
                        listener.onTransferPacket(p);
                    }
                    break;
                default:
                    if (state == State.CONNECTED && Hrp.isTransferMessage(p.type)) {
                        listener.onTransferPacket(p);
                    }
            }
        } catch (Messages.MalformedPacketException e) {
            Log.w(TAG, Hrp.typeName(p.type) + " mal formé : " + e.getMessage());
        }
    }

    /** 15 s sans aucun datagramme de l'hôte ⇒ hôte perdu. */
    private void checkHost() {
        if (state == State.CONNECTED && System.currentTimeMillis() - lastHostPacket > timeoutMs) {
            disconnect("Hôte perdu (plus de nouvelles depuis " + timeoutMs / 1000 + " s)");
        }
    }

    // ------------------------------------------------------------------
    // Fin
    // ------------------------------------------------------------------

    private void fail(String reason) {
        Log.w(TAG, "échec : " + reason);
        shutdown();
        listener.onJoinFailed(reason);
    }

    private void disconnect(String reason) {
        Log.i(TAG, "déconnecté : " + reason);
        shutdown();
        listener.onDisconnected(reason);
    }

    private void shutdown() {
        if (state == State.CLOSED) return;
        state = State.CLOSED;
        if (retry != null) retry.cancel(false);
        if (transport != null) transport.close(); // laisse partir un éventuel BYE
        loop.shutdown(); // pas de nouvelles tâches ; la tâche en cours se termine
    }

    /** Pour les tests : coupe tout sans BYE, comme un téléphone qui perd le Wi-Fi. */
    void killForTest() {
        post(() -> {
            state = State.CLOSED;
            if (transport != null) transport.close();
            loop.shutdownNow();
        });
    }
}
