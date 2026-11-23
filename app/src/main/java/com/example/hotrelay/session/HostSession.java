package com.example.hotrelay.session;

import android.util.Log;

import com.example.hotrelay.network.UdpTransport;
import com.example.hotrelay.protocol.Hrp;
import com.example.hotrelay.protocol.HrpPacket;
import com.example.hotrelay.protocol.Messages;

import java.net.InetAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Session côté hôte (#15, #17) — protocole §8.
 *
 * <ul>
 *   <li>NEW → attribue un ID, répond JOIN (même JOIN si le NEW est renvoyé), diffuse SYNCTABLE ;</li>
 *   <li>SYNCTABLE à tous à chaque changement et toutes les 5 s (signe de vie de l'hôte) ;</li>
 *   <li>tout datagramme d'un client = signe de vie ; 15 s de silence ou BYE ⇒ client retiré ;</li>
 *   <li>{@link #stop()} envoie EXIT 3 fois puis ferme le socket.</li>
 * </ul>
 *
 * <p>Tout le traitement se fait sur un seul thread ({@code hrp-host}) : pas besoin de verrous.
 * Les callbacks du {@link Listener} arrivent sur ce thread : utiliser {@code runOnUiThread}
 * pour toucher aux vues.</p>
 */
public final class HostSession {

    private static final String TAG = "HostSession";

    public interface Listener {
        /** La liste a changé. Contient l'hôte (ID 0) en premier. */
        void onParticipantsChanged(List<Messages.Participant> participants);

        /** Un client est parti (BYE ou silence). Ses transferts sont déjà interrompus par le relais. */
        default void onParticipantLeft(ParticipantRegistry.Entry participant, String reason) {}
    }

    private final String hostName;
    private final int port;
    private final Listener listener;
    private final ParticipantRegistry registry = new ParticipantRegistry();
    private final TransferRouter router = new TransferRouter(new TransferRouter.Sink() {
        @Override
        public boolean sendTo(int participantId, HrpPacket packet) {
            return HostSession.this.sendTo(participantId, packet);
        }

        @Override
        public boolean isConnected(int participantId) {
            return registry.get(participantId) != null;
        }
    });

    // Modifiables par les tests (valeurs du protocole par défaut)
    long heartbeatMs = Hrp.HEARTBEAT_MS;
    long timeoutMs = Hrp.PEER_TIMEOUT_MS;

    private UdpTransport transport;
    private ScheduledExecutorService loop;
    private volatile boolean running;

    public HostSession(String hostName, Listener listener) {
        this(hostName, Hrp.PORT, listener);
    }

    HostSession(String hostName, int port, Listener listener) {
        this.hostName = hostName;
        this.port = port;
        this.listener = listener;
    }

    /** Ouvre le port 50000 et démarre la session. */
    public synchronized void start() throws SocketException {
        if (running) return;
        loop = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "hrp-host"));
        transport = new UdpTransport(port);
        try {
            transport.start((data, from, p) -> post(() -> onPacket(data, from, p)));
        } catch (SocketException e) {
            loop.shutdownNow();
            throw e;
        }
        running = true;
        loop.scheduleWithFixedDelay(this::heartbeat, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
        long check = Math.max(50, Math.min(1000, timeoutMs / 4));
        loop.scheduleWithFixedDelay(this::expireSilent, check, check, TimeUnit.MILLISECONDS);
        post(this::notifyParticipants);
        Log.i(TAG, "session ouverte par « " + hostName + " »");
    }

    /** Ferme la session : EXIT ×3 à tous les clients, puis fermeture du socket. */
    public synchronized void stop() {
        if (!running) return;
        running = false;
        byte[] exit = Messages.exit().encode();
        for (int i = 0; i < Hrp.CONTROL_REPEAT; i++) {
            for (ParticipantRegistry.Entry e : registry.list()) {
                transport.send(exit, e.address, e.port);
            }
        }
        transport.close(); // attend que les EXIT soient partis
        loop.shutdownNow();
        Log.i(TAG, "session fermée");
    }

    public boolean isRunning() {
        return running;
    }

    /** Participants actuels, hôte compris (ID 0 en premier). */
    public List<Messages.Participant> getParticipants() {
        List<Messages.Participant> list = new ArrayList<>();
        list.add(new Messages.Participant(Hrp.HOST_ID, hostName));
        for (ParticipantRegistry.Entry e : registry.list()) {
            list.add(new Messages.Participant(e.id, e.name));
        }
        return list;
    }

    /**
     * L'hôte veut lui aussi envoyer / recevoir des fichiers : les messages de transfert
     * dont il est SRC ou DST arrivent ici (sur le thread de la session).
     * Sans endpoint, un READY vers l'hôte reçoit ERROR UNKNOWN_PARTICIPANT.
     */
    public void setLocalEndpoint(TransferRouter.LocalEndpoint endpoint) {
        post(() -> router.setLocalEndpoint(endpoint));
    }

    /** Message de transfert émis par l'hôte (ID 0) : passe par le relais comme ceux des clients. */
    public void sendFromHost(HrpPacket packet) {
        post(() -> router.fromHost(packet));
    }

    TransferRouter getRouter() {
        return router;
    }

    public ParticipantRegistry getRegistry() {
        return registry;
    }

    /** Envoie un paquet à un client par son ID (utilisé par le relais #21). */
    public boolean sendTo(int participantId, HrpPacket packet) {
        ParticipantRegistry.Entry e = registry.get(participantId);
        if (e == null || !running) return false;
        transport.send(packet.encode(), e.address, e.port);
        return true;
    }

    /** Envoie des octets déjà encodés tels quels (relais sans ré-encodage). */
    public boolean sendRawTo(int participantId, byte[] datagram) {
        ParticipantRegistry.Entry e = registry.get(participantId);
        if (e == null || !running) return false;
        transport.send(datagram, e.address, e.port);
        return true;
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
    // Réception
    // ------------------------------------------------------------------

    private void onPacket(byte[] data, InetAddress from, int fromPort) {
        if (!running) return;
        HrpPacket p = HrpPacket.decode(data);
        if (p == null) {
            Log.d(TAG, "paquet invalide ignoré de " + from.getHostAddress() + ":" + fromPort);
            return;
        }
        ParticipantRegistry.Entry sender = registry.findByEndpoint(from, fromPort);
        if (sender != null) registry.touch(sender.id); // tout datagramme = signe de vie

        try {
            switch (p.type) {
                case Hrp.NEW:
                    handleNew(Messages.New.parse(p), from, fromPort);
                    break;
                case Hrp.AVAILABLE:
                    if (sender == null) Log.d(TAG, "AVAILABLE d'un inconnu ignoré");
                    break;
                case Hrp.BYE:
                    if (sender != null) removeParticipant(sender, "BYE");
                    break;
                default:
                    if (sender == null) {
                        Log.d(TAG, p + " d'un inconnu ignoré");
                    } else if (Hrp.isTransferMessage(p.type)) {
                        router.onPacket(p, sender.id);
                    } else {
                        Log.d(TAG, p + " ignoré (réservé à l'hôte)");
                    }
            }
        } catch (Messages.MalformedPacketException e) {
            Log.w(TAG, Hrp.typeName(p.type) + " mal formé : " + e.getMessage());
        }
    }

    private void handleNew(Messages.New m, InetAddress from, int fromPort) {
        // Renvoi d'une demande déjà acceptée : même JOIN, pas de second participant.
        ParticipantRegistry.Entry known = registry.findByRequestId(m.requestId);
        if (known != null) {
            Log.d(TAG, "NEW renvoyé par " + known + " : même JOIN");
            send(new Messages.Join(known.id, hostName, m.requestId).toPacket(), from, fromPort);
            return;
        }
        // Nouvelle tentative depuis la même adresse : l'ancienne entrée est périmée.
        ParticipantRegistry.Entry stale = registry.findByEndpoint(from, fromPort);
        if (stale != null) removeParticipant(stale, "nouvelle demande NEW");

        ParticipantRegistry.Entry e = registry.add(m.name, from, fromPort, m.requestId);
        if (e == null) {
            Log.i(TAG, "session pleine, refus de « " + m.name + " »");
            send(new Messages.ErrorMessage(Hrp.ERR_SESSION_FULL, 0).toPacket(), from, fromPort);
            return;
        }
        Log.i(TAG, "JOIN " + e);
        send(new Messages.Join(e.id, hostName, m.requestId).toPacket(), from, fromPort);
        broadcastTable();
        notifyParticipants();
    }

    // ------------------------------------------------------------------
    // Minuteries
    // ------------------------------------------------------------------

    /** Toutes les 5 s : SYNCTABLE à tous, signe de vie de l'hôte même sans changement. */
    private void heartbeat() {
        if (running) broadcastTable();
    }

    /** Chaque seconde : retire les clients silencieux depuis 15 s. */
    private void expireSilent() {
        if (!running) return;
        long now = System.currentTimeMillis();
        for (ParticipantRegistry.Entry e : registry.expired(now, timeoutMs)) {
            removeParticipant(e, "silence de " + (now - e.lastSeen) + " ms");
        }
        router.expireIdle(now);
    }

    private void removeParticipant(ParticipantRegistry.Entry e, String reason) {
        if (registry.remove(e.id) == null) return;
        Log.i(TAG, "départ (" + reason + ") : " + e);
        router.onParticipantLeft(e.id);
        listener.onParticipantLeft(e, reason);
        broadcastTable();
        notifyParticipants();
    }

    private void broadcastTable() {
        byte[] table = new Messages.SyncTable(getParticipants()).toPacket().encode();
        for (ParticipantRegistry.Entry e : registry.list()) {
            transport.send(table, e.address, e.port);
        }
    }

    private void notifyParticipants() {
        listener.onParticipantsChanged(getParticipants());
    }

    private void send(HrpPacket p, InetAddress ip, int toPort) {
        transport.send(p.encode(), ip, toPort);
    }
}
