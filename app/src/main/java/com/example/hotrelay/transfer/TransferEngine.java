package com.example.hotrelay.transfer;

import android.util.Log;

import com.example.hotrelay.protocol.Hrp;
import com.example.hotrelay.protocol.HrpPacket;
import com.example.hotrelay.protocol.Messages;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Moteur de transfert de fichiers (#22, #25) — protocole §9 et §10.
 *
 * <pre>
 * ÉMETTEUR                         DESTINATAIRE
 * READY ─────────────────────────▶ crée le fichier (en attente)
 *       ◀───────────────────────── YES_READY      (acceptation automatique)
 * TAKE 0 ────────────────────────▶ écrit, SHA-256 au fil de l'eau
 *       ◀───────────────────────── NICE 0
 * …un bloc à la fois (Stop-and-Wait)…
 * FINISH (SHA-256) ──────────────▶ compare l'empreinte
 *       ◀───────────────────────── COMPLETE       (ou ERROR HASH_MISMATCH)
 * </pre>
 *
 * <p>Sans réponse : renvoi du même message après 500 ms, 1 s, 2 s, 4 s, 4 s, puis échec
 * (ERROR TRANSFER_TIMEOUT). Le destinataire abandonne après 15 s de silence.</p>
 *
 * <p>Le moteur ne connaît ni l'hôte ni les sockets : il émet par un {@link PacketSender}
 * (client → {@code ClientSession.send}, hôte → {@code HostSession.sendFromHost}) et reçoit
 * par {@link #onPacket}. Tout tourne sur un seul thread ({@code hrp-transfer}) :
 * la lecture et l'écriture des fichiers ne bloquent jamais le réseau ni l'interface.</p>
 */
public final class TransferEngine {

    private static final String TAG = "TransferEngine";
    /** Au plus une mise à jour de progression toutes les 100 ms par transfert. */
    private static final long PROGRESS_INTERVAL_MS = 100;

    /** Envoie un paquet vers l'hôte (qui le relaie). */
    public interface PacketSender {
        void send(HrpPacket packet);
    }

    /** Ouvre le contenu du fichier à envoyer. */
    public interface Source {
        InputStream open() throws IOException;
    }

    /** Appelé sur le thread du moteur à chaque changement d'un transfert. */
    public interface Listener {
        void onTransferChanged(Transfer transfer);
    }

    private final int myId;
    private final PacketSender out;
    private final FileStorage storage;
    private final Listener listener;
    private final ScheduledExecutorService loop =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "hrp-transfer"));
    private final AtomicInteger counter = new AtomicInteger();
    private final Map<Integer, Outgoing> outgoing = new HashMap<>();
    private final Map<Integer, Incoming> incoming = new HashMap<>();

    // Modifiable par les tests
    long peerTimeoutMs = Hrp.PEER_TIMEOUT_MS;

    public TransferEngine(int myId, PacketSender out, FileStorage storage, Listener listener) {
        this.myId = myId;
        this.out = out;
        this.storage = storage;
        this.listener = listener;
        long check = 1000;
        loop.scheduleWithFixedDelay(this::checkSilentIncoming, check, check, TimeUnit.MILLISECONDS);
    }

    // =====================================================================
    // API publique (appelable depuis n'importe quel thread)
    // =====================================================================

    /**
     * Commence l'envoi d'un fichier à {@code dstId}.
     *
     * @return le TRANSFER_ID attribué (ID de ce téléphone × 2^24 + compteur).
     */
    public int send(String fileName, long fileSize, Source source, int dstId) {
        int tid = Hrp.transferId(myId, counter.incrementAndGet());
        post(() -> startOutgoing(tid, fileName, fileSize, source, dstId));
        return tid;
    }

    /** L'émetteur annule : CANCEL ×3, sans accusé (§7). */
    public void cancel(int transferId) {
        post(() -> {
            Outgoing o = outgoing.get(transferId);
            if (o == null || o.state.isFinished()) return;
            HrpPacket cancel = new Messages.Cancel(transferId).toPacket();
            for (int i = 0; i < Hrp.CONTROL_REPEAT; i++) out.send(cancel);
            finish(o, Transfer.State.CANCELLED, null);
        });
    }

    /** Message de transfert reçu (READY, YES_READY, TAKE, NICE, FINISH, COMPLETE, ERROR, CANCEL). */
    public void onPacket(HrpPacket p) {
        post(() -> handle(p));
    }

    /** Fin de session : tous les transferts en cours échouent, fichiers partiels supprimés. */
    public void shutdown(String reason) {
        post(() -> {
            for (Outgoing o : new ArrayList<>(outgoing.values())) {
                if (!o.state.isFinished()) finish(o, Transfer.State.FAILED, reason);
            }
            for (Incoming in : new ArrayList<>(incoming.values())) {
                if (!in.state.isFinished()) fail(in, reason, 0);
            }
            loop.shutdown();
        });
    }

    private void post(Runnable r) {
        try {
            loop.execute(() -> {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    Log.e(TAG, "erreur interne", e); // ne jamais tuer le thread du moteur
                }
            });
        } catch (RejectedExecutionException ignored) {
            // moteur arrêté
        }
    }

    // =====================================================================
    // Réception des paquets
    // =====================================================================

    private void handle(HrpPacket p) {
        try {
            switch (p.type) {
                case Hrp.READY: onReady(Messages.Ready.parse(p)); break;
                case Hrp.YES_READY: onYesReady(Messages.YesReady.parse(p).transferId); break;
                case Hrp.TAKE: onTake(Messages.Take.parse(p)); break;
                case Hrp.NICE: onNice(Messages.Nice.parse(p)); break;
                case Hrp.FINISH: onFinish(Messages.Finish.parse(p)); break;
                case Hrp.COMPLETE: onComplete(Messages.Complete.parse(p).transferId); break;
                case Hrp.CANCEL: onCancel(Messages.Cancel.parse(p).transferId); break;
                case Hrp.ERROR: onError(Messages.ErrorMessage.parse(p)); break;
                default: break;
            }
        } catch (Messages.MalformedPacketException e) {
            Log.w(TAG, Hrp.typeName(p.type) + " mal formé : " + e.getMessage());
        }
    }

    // =====================================================================
    // Émetteur
    // =====================================================================

    private final class Outgoing {
        final int tid;
        final int dst;
        final String name;
        final long size;
        final long total;
        final Source source;
        InputStream in;
        MessageDigest md;
        Transfer.State state = Transfer.State.WAITING_ACCEPT;
        /** Bloc envoyé et pas encore confirmé par NICE. */
        long seq;
        /** Paquet en attente de réponse, renvoyé tel quel en cas de silence. */
        HrpPacket pending;
        int attempt;
        int generation;
        ScheduledFuture<?> timer;
        String error;
        long lastNotify;

        Outgoing(int tid, int dst, String name, long size, Source source) {
            this.tid = tid;
            this.dst = dst;
            this.name = name;
            this.size = size;
            this.total = Hrp.totalBlocks(size);
            this.source = source;
        }

        Transfer snapshot() {
            return new Transfer(tid, true, dst, name, size, seq, total, state, error, null);
        }
    }

    private void startOutgoing(int tid, String name, long size, Source source, int dst) {
        Outgoing o = new Outgoing(tid, dst, name, size, source);
        outgoing.put(tid, o);
        try {
            o.in = source.open();
            o.md = sha256();
        } catch (IOException e) {
            finish(o, Transfer.State.FAILED, "lecture du fichier : " + e.getMessage());
            return;
        }
        Log.i(TAG, String.format("envoi %08X « %s » %d o (%d blocs) → %d", tid, name, size, o.total, dst));
        notify(o.snapshot());
        expectReply(o, new Messages.Ready(myId, dst, tid, name, size).toPacket());
    }

    private void onYesReady(int tid) {
        Outgoing o = outgoing.get(tid);
        if (o == null || o.state != Transfer.State.WAITING_ACCEPT) return; // doublon
        o.state = Transfer.State.SENDING;
        sendCurrentBlock(o);
    }

    private void onNice(Messages.Nice nice) {
        Outgoing o = outgoing.get(nice.transferId);
        // Seul le NICE du bloc en attente compte ; les autres (doublons, retards) sont ignorés.
        if (o == null || o.state != Transfer.State.SENDING || nice.seq != o.seq) return;
        o.seq++;
        notifyProgress(o);
        sendCurrentBlock(o);
    }

    /** Envoie le bloc {@code o.seq}, ou FINISH si tous les blocs sont confirmés. */
    private void sendCurrentBlock(Outgoing o) {
        if (o.seq >= o.total) {
            o.state = Transfer.State.VERIFYING;
            byte[] sha = o.md.digest();
            closeQuietly(o.in);
            notify(o.snapshot());
            expectReply(o, new Messages.Finish(o.tid, sha).toPacket());
            return;
        }
        int len = (int) Math.min(Hrp.MAX_DATA, o.size - o.seq * Hrp.MAX_DATA);
        byte[] block = new byte[len];
        try {
            readFully(o.in, block);
        } catch (IOException e) {
            failOutgoing(o, "lecture du fichier : " + e.getMessage(), Hrp.ERR_TRANSFER_FAILED);
            return;
        }
        o.md.update(block);
        expectReply(o, new Messages.Take(o.tid, o.seq, o.total, block).toPacket());
    }

    private void onComplete(int tid) {
        Outgoing o = outgoing.get(tid);
        if (o == null || o.state != Transfer.State.VERIFYING) return;
        Log.i(TAG, String.format("envoi %08X terminé", tid));
        finish(o, Transfer.State.COMPLETED, null);
    }

    // ---- Renvois (§10) ----

    /** Envoie {@code packet} et le renvoie tant que la réponse attendue n'arrive pas. */
    private void expectReply(Outgoing o, HrpPacket packet) {
        cancelTimer(o);
        o.pending = packet;
        o.attempt = 0;
        o.generation++;
        out.send(packet);
        schedule(o);
    }

    private void schedule(Outgoing o) {
        int gen = o.generation;
        o.timer = loop.schedule(() -> onReplyTimeout(o, gen),
                Hrp.retryDelayMs(o.attempt + 1), TimeUnit.MILLISECONDS);
    }

    private void onReplyTimeout(Outgoing o, int gen) {
        if (gen != o.generation || o.state.isFinished()) return; // réponse arrivée entre-temps
        if (o.attempt >= Hrp.MAX_RETRIES) {
            failOutgoing(o, "pas de réponse du destinataire", Hrp.ERR_TRANSFER_TIMEOUT);
            return;
        }
        o.attempt++;
        Log.d(TAG, String.format("%08X : renvoi %d de %s", o.tid, o.attempt, Hrp.typeName(o.pending.type)));
        out.send(o.pending);
        schedule(o);
    }

    private void cancelTimer(Outgoing o) {
        if (o.timer != null) o.timer.cancel(false);
        o.timer = null;
    }

    private void failOutgoing(Outgoing o, String reason, int errorCode) {
        out.send(new Messages.ErrorMessage(errorCode, o.tid).toPacket());
        finish(o, Transfer.State.FAILED, reason);
    }

    private void finish(Outgoing o, Transfer.State state, String error) {
        cancelTimer(o);
        o.generation++;
        o.state = state;
        o.error = error;
        closeQuietly(o.in);
        if (error != null) Log.w(TAG, String.format("envoi %08X : %s (%s)", o.tid, state, error));
        notify(o.snapshot());
    }

    // =====================================================================
    // Destinataire
    // =====================================================================

    private final class Incoming {
        final int tid;
        final int src;
        final String name;
        final long size;
        final long total;
        FileStorage.Output output;
        final MessageDigest md;
        Transfer.State state = Transfer.State.SENDING;
        /** Prochain SEQ attendu = nombre de blocs déjà écrits. */
        long expected;
        long lastActivity = System.currentTimeMillis();
        String error;
        String location;
        long lastNotify;

        Incoming(Messages.Ready r) {
            this.tid = r.transferId;
            this.src = r.src;
            this.name = r.fileName;
            this.size = r.fileSize;
            this.total = Hrp.totalBlocks(r.fileSize);
            this.md = sha256();
        }

        Transfer snapshot() {
            return new Transfer(tid, false, src, name, size, expected, total, state, error, location);
        }
    }

    private void onReady(Messages.Ready r) {
        if (r.dst != myId) return;
        Incoming known = incoming.get(r.transferId);
        if (known != null) {
            // READY renvoyé (YES_READY perdu) : même réponse (§9, doublons)
            if (known.state == Transfer.State.SENDING) out.send(new Messages.YesReady(r.transferId).toPacket());
            return;
        }
        Incoming in = new Incoming(r);
        incoming.put(in.tid, in);
        try {
            in.output = storage.create(r.fileName, r.fileSize);
        } catch (IOException e) {
            in.state = Transfer.State.FAILED;
            in.error = "enregistrement impossible : " + e.getMessage();
            out.send(new Messages.ErrorMessage(Hrp.ERR_TRANSFER_REJECTED, in.tid).toPacket());
            notify(in.snapshot());
            return;
        }
        Log.i(TAG, String.format("réception %08X « %s » %d o de %d", in.tid, in.name, in.size, in.src));
        notify(in.snapshot());
        // MVP : acceptation automatique (§7, YES_READY)
        out.send(new Messages.YesReady(in.tid).toPacket());
    }

    private void onTake(Messages.Take t) {
        Incoming in = incoming.get(t.transferId);
        if (in == null || in.state != Transfer.State.SENDING) return; // inconnu, annulé ou fini
        if (t.total != in.total) return;
        long expectedLen = t.seq == in.total - 1 ? in.size - t.seq * Hrp.MAX_DATA : Hrp.MAX_DATA;
        if (t.data.length != expectedLen) return;
        in.lastActivity = System.currentTimeMillis();

        if (t.seq == in.expected) {
            try {
                in.output.stream().write(t.data);
            } catch (IOException e) {
                fail(in, "écriture : " + e.getMessage(), Hrp.ERR_TRANSFER_FAILED);
                return;
            }
            in.md.update(t.data);
            in.expected++;
            out.send(new Messages.Nice(in.tid, t.seq).toPacket());
            notifyProgress(in);
        } else if (t.seq < in.expected) {
            // Doublon (notre NICE s'est perdu) : on ne réécrit pas, on reconfirme.
            out.send(new Messages.Nice(in.tid, t.seq).toPacket());
        }
        // SEQ > attendu : ignoré (§9)
    }

    private void onFinish(Messages.Finish f) {
        Incoming in = incoming.get(f.transferId);
        if (in == null) return;
        in.lastActivity = System.currentTimeMillis();
        if (in.state == Transfer.State.COMPLETED) {
            out.send(new Messages.Complete(in.tid).toPacket()); // COMPLETE perdu : on le renvoie
            return;
        }
        if (in.state != Transfer.State.SENDING || in.expected != in.total) return;

        in.state = Transfer.State.VERIFYING;
        byte[] sha = in.md.digest();
        if (!Arrays.equals(sha, f.sha256)) {
            fail(in, "empreinte SHA-256 différente", Hrp.ERR_HASH_MISMATCH);
            return;
        }
        try {
            in.location = in.output.commit();
        } catch (IOException e) {
            fail(in, "enregistrement : " + e.getMessage(), Hrp.ERR_TRANSFER_FAILED);
            return;
        }
        in.state = Transfer.State.COMPLETED;
        Log.i(TAG, String.format("réception %08X terminée, SHA-256 vérifié → %s", in.tid, in.location));
        out.send(new Messages.Complete(in.tid).toPacket());
        notify(in.snapshot());
    }

    private void onCancel(int tid) {
        Incoming in = incoming.get(tid);
        if (in == null || in.state.isFinished()) return; // déjà annulé : sans effet
        if (in.output != null) in.output.abort();
        in.state = Transfer.State.CANCELLED;
        in.error = "annulé par l'expéditeur";
        notify(in.snapshot());
    }

    /** Destinataire : 15 s sans rien de l'émetteur ⇒ abandon (§9). */
    private void checkSilentIncoming() {
        long now = System.currentTimeMillis();
        for (Incoming in : new ArrayList<>(incoming.values())) {
            if (in.state == Transfer.State.SENDING && now - in.lastActivity > peerTimeoutMs) {
                fail(in, "plus de nouvelles de l'expéditeur", Hrp.ERR_TRANSFER_TIMEOUT);
            }
        }
    }

    /** @param errorCode 0 = ne rien envoyer à l'autre partie. */
    private void fail(Incoming in, String reason, int errorCode) {
        if (in.output != null) in.output.abort(); // fichier partiel supprimé (§11)
        in.state = Transfer.State.FAILED;
        in.error = reason;
        if (errorCode != 0) out.send(new Messages.ErrorMessage(errorCode, in.tid).toPacket());
        Log.w(TAG, String.format("réception %08X échouée : %s", in.tid, reason));
        notify(in.snapshot());
    }

    // =====================================================================
    // Erreurs reçues
    // =====================================================================

    private void onError(Messages.ErrorMessage e) {
        String reason = errorText(e.code);
        Outgoing o = outgoing.get(e.transferId);
        if (o != null && !o.state.isFinished()) {
            finish(o, Transfer.State.FAILED, reason); // jamais d'ERROR en réponse à un ERROR
            return;
        }
        Incoming in = incoming.get(e.transferId);
        if (in != null && !in.state.isFinished()) fail(in, reason, 0);
    }

    private static String errorText(int code) {
        switch (code) {
            case Hrp.ERR_UNKNOWN_PARTICIPANT: return "destinataire introuvable";
            case Hrp.ERR_UNKNOWN_TRANSFER: return "transfert inconnu de l'hôte";
            case Hrp.ERR_TRANSFER_REJECTED: return "refusé par le destinataire";
            case Hrp.ERR_HASH_MISMATCH: return "fichier corrompu (SHA-256 différent)";
            case Hrp.ERR_TRANSFER_TIMEOUT: return "délai dépassé";
            case Hrp.ERR_TRANSFER_FAILED: return "l'autre appareil a quitté la session";
            default: return "erreur " + Hrp.errorName(code);
        }
    }

    // =====================================================================
    // Outils
    // =====================================================================

    private void notifyProgress(Outgoing o) {
        long now = System.currentTimeMillis();
        if (now - o.lastNotify < PROGRESS_INTERVAL_MS) return;
        o.lastNotify = now;
        notify(o.snapshot());
    }

    private void notifyProgress(Incoming in) {
        long now = System.currentTimeMillis();
        if (now - in.lastNotify < PROGRESS_INTERVAL_MS && in.expected < in.total) return;
        in.lastNotify = now;
        notify(in.snapshot());
    }

    private void notify(Transfer t) {
        try {
            listener.onTransferChanged(t);
        } catch (RuntimeException e) {
            Log.e(TAG, "erreur dans le listener", e);
        }
    }

    /** Transferts connus (pour les tests). */
    List<Transfer> snapshotAll() {
        List<Transfer> l = new ArrayList<>();
        for (Outgoing o : outgoing.values()) l.add(o.snapshot());
        for (Incoming in : incoming.values()) l.add(in.snapshot());
        return l;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // toujours présent sur Android
        }
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new IOException("fichier plus court que prévu");
            off += n;
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (IOException ignored) {
            // rien à faire
        }
    }
}
