package com.example.hotrelay.session;

import android.util.Log;

import com.example.hotrelay.protocol.Hrp;
import com.example.hotrelay.protocol.HrpPacket;
import com.example.hotrelay.protocol.Messages;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Relais de l'hôte (#21, #26) — protocole §2, §7, §9, §11.
 *
 * <p>L'hôte ne stocke aucun fichier : il garde seulement, pour chaque TRANSFER_ID,
 * qui envoie (SRC) et qui reçoit (DST).</p>
 *
 * <ul>
 *   <li><b>READY</b> : vérifie SRC = expéditeur réel, DST connecté, 1er octet du
 *       TRANSFER_ID = SRC, TRANSFER_ID inédit ; mémorise la route puis relaie.</li>
 *   <li><b>TAKE, FINISH, CANCEL</b> : seulement de SRC vers DST.</li>
 *   <li><b>YES_READY, NICE, COMPLETE</b> : seulement de DST vers SRC.</li>
 *   <li><b>ERROR</b> : dans les deux sens.</li>
 * </ul>
 *
 * <p>Le relais renvoie le datagramme tel quel : l'hôte ne fabrique jamais de NICE.
 * Chaque transfert a sa route : deux transferts simultanés ne se mélangent pas (#26).</p>
 *
 * <p>L'hôte (ID 0) peut lui-même envoyer ou recevoir : ses paquets entrent par
 * {@link #fromHost} et ceux qui lui sont destinés sortent par {@link LocalEndpoint}.</p>
 *
 * <p>Utilisé uniquement sur le thread de {@link HostSession} : pas de verrou.</p>
 */
public final class TransferRouter {

    private static final String TAG = "TransferRouter";
    /** Une route sans activité depuis ce délai est oubliée (le destinataire abandonne à 15 s). */
    static final long ROUTE_IDLE_MS = 2 * Hrp.PEER_TIMEOUT_MS;

    /** Reçoit les messages de transfert destinés à l'hôte lui-même (DST = 0 ou SRC = 0). */
    public interface LocalEndpoint {
        void onPacket(HrpPacket packet);
    }

    /** Ce dont le routeur a besoin de l'hôte. */
    interface Sink {
        boolean sendTo(int participantId, HrpPacket packet);
        boolean isConnected(int participantId);
    }

    static final class Route {
        final int transferId;
        final int src;
        final int dst;
        long lastActivity;
        boolean completed;

        Route(int transferId, int src, int dst, long now) {
            this.transferId = transferId;
            this.src = src;
            this.dst = dst;
            this.lastActivity = now;
        }

        int other(int id) {
            return id == src ? dst : src;
        }

        @Override
        public String toString() {
            return String.format("%08X %d→%d", transferId, src, dst);
        }
    }

    private final Sink sink;
    private final Map<Integer, Route> routes = new HashMap<>();
    private LocalEndpoint local;

    TransferRouter(Sink sink) {
        this.sink = sink;
    }

    void setLocalEndpoint(LocalEndpoint endpoint) {
        this.local = endpoint;
    }

    /** Nombre de transferts en cours (pour les logs et les tests). */
    int activeRoutes() {
        return routes.size();
    }

    // ------------------------------------------------------------------
    // Entrées
    // ------------------------------------------------------------------

    /** Message de transfert reçu d'un client connu. */
    void onPacket(HrpPacket p, int fromId) {
        route(p, fromId);
    }

    /** Message de transfert émis par l'hôte lui-même (ID 0). */
    void fromHost(HrpPacket p) {
        route(p, Hrp.HOST_ID);
    }

    /** Un participant est parti : ses transferts échouent, l'autre partie est prévenue (§11). */
    void onParticipantLeft(int id) {
        Iterator<Route> it = routes.values().iterator();
        while (it.hasNext()) {
            Route r = it.next();
            if (r.src != id && r.dst != id) continue;
            it.remove();
            if (!r.completed) {
                Log.i(TAG, "transfert " + r + " interrompu : départ de " + id);
                deliver(r.other(id), new Messages.ErrorMessage(Hrp.ERR_TRANSFER_FAILED, r.transferId).toPacket());
            }
        }
    }

    /** Oublie les routes inactives (appelé régulièrement par l'hôte). */
    void expireIdle(long now) {
        Iterator<Route> it = routes.values().iterator();
        while (it.hasNext()) {
            Route r = it.next();
            if (now - r.lastActivity > ROUTE_IDLE_MS) {
                Log.d(TAG, "route oubliée (inactive) : " + r);
                it.remove();
            }
        }
    }

    // ------------------------------------------------------------------
    // Routage
    // ------------------------------------------------------------------

    private void route(HrpPacket p, int fromId) {
        if (p.type == Hrp.READY) {
            onReady(p, fromId);
            return;
        }
        int tid = p.transferId();
        Route r = routes.get(tid);
        if (r == null) {
            if (p.type == Hrp.ERROR) return; // jamais d'ERROR en réponse à un ERROR
            Log.d(TAG, Hrp.typeName(p.type) + String.format(" pour un transfert inconnu %08X", tid));
            reply(fromId, Hrp.ERR_UNKNOWN_TRANSFER, tid);
            return;
        }
        if (!allowed(p.type, r, fromId)) {
            Log.w(TAG, Hrp.typeName(p.type) + " de " + fromId + " refusé pour " + r);
            return;
        }
        r.lastActivity = System.currentTimeMillis();
        int to = r.other(fromId);
        if (!deliver(to, p)) {
            routes.remove(tid);
            reply(fromId, Hrp.ERR_UNKNOWN_PARTICIPANT, tid);
            return;
        }
        switch (p.type) {
            case Hrp.COMPLETE:
                r.completed = true; // on garde la route un moment : FINISH renvoyé ⇒ COMPLETE renvoyé
                Log.i(TAG, "transfert terminé : " + r);
                break;
            case Hrp.CANCEL:
            case Hrp.ERROR:
                r.completed = true; // fin, mais les doublons (CANCEL ×3) doivent encore passer
                break;
            default:
                break;
        }
    }

    private void onReady(HrpPacket p, int fromId) {
        Messages.Ready m;
        try {
            m = Messages.Ready.parse(p);
        } catch (Messages.MalformedPacketException e) {
            Log.w(TAG, "READY mal formé : " + e.getMessage());
            reply(fromId, Hrp.ERR_INVALID_MESSAGE, p.transferId());
            return;
        }
        // Contrôles du §7 (READY) et du §12
        if (m.src != fromId || Hrp.senderOf(m.transferId) != fromId) {
            Log.w(TAG, "READY usurpé : SRC=" + m.src + " mais reçu de " + fromId);
            reply(fromId, Hrp.ERR_INVALID_MESSAGE, m.transferId);
            return;
        }
        boolean dstReachable = m.dst == Hrp.HOST_ID ? local != null : sink.isConnected(m.dst);
        if (m.dst == m.src || !dstReachable) {
            reply(fromId, Hrp.ERR_UNKNOWN_PARTICIPANT, m.transferId);
            return;
        }
        Route existing = routes.get(m.transferId);
        if (existing != null) {
            if (existing.src != m.src || existing.dst != m.dst) {
                reply(fromId, Hrp.ERR_INVALID_MESSAGE, m.transferId); // TRANSFER_ID déjà pris
                return;
            }
            // READY renvoyé (YES_READY perdu) : on relaie à nouveau, le destinataire répondra pareil.
        } else {
            routes.put(m.transferId, new Route(m.transferId, m.src, m.dst, System.currentTimeMillis()));
            Log.i(TAG, "nouveau transfert " + routes.get(m.transferId) + " « " + m.fileName
                    + " » " + m.fileSize + " o (" + routes.size() + " en cours)");
        }
        routes.get(m.transferId).lastActivity = System.currentTimeMillis();
        if (!deliver(m.dst, p)) {
            routes.remove(m.transferId);
            reply(fromId, Hrp.ERR_UNKNOWN_PARTICIPANT, m.transferId);
        }
    }

    /** Qui a le droit d'envoyer quoi (§6). */
    private static boolean allowed(int type, Route r, int fromId) {
        switch (type) {
            case Hrp.TAKE:
            case Hrp.FINISH:
            case Hrp.CANCEL:
                return fromId == r.src;
            case Hrp.YES_READY:
            case Hrp.NICE:
            case Hrp.COMPLETE:
                return fromId == r.dst;
            case Hrp.ERROR:
                return fromId == r.src || fromId == r.dst;
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------
    // Sorties
    // ------------------------------------------------------------------

    /** Envoie vers un participant, ou vers l'hôte lui-même si l'ID est 0. */
    private boolean deliver(int to, HrpPacket p) {
        if (to == Hrp.HOST_ID) {
            if (local == null) return false;
            local.onPacket(p);
            return true;
        }
        return sink.sendTo(to, p);
    }

    private void reply(int to, int code, int tid) {
        deliver(to, new Messages.ErrorMessage(code, tid).toPacket());
    }

    /** Pour les tests. */
    List<Route> snapshot() {
        return new ArrayList<>(routes.values());
    }
}
