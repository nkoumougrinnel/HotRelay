package com.example.hotrelay.network;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Couche transport UDP (#10) : un socket, un thread de réception, un thread d'envoi.
 *
 * <p>Ne connaît rien au protocole HRP : elle transporte des octets.
 * Le réseau est interdit sur le thread UI (NetworkOnMainThreadException),
 * donc {@link #send} confie l'envoi à un thread dédié et revient tout de suite.</p>
 *
 * <pre>
 * UdpTransport t = new UdpTransport(Hrp.PORT);          // hôte : port 50000
 * UdpTransport t = new UdpTransport(0);                 // client : port libre
 * t.start((data, from, port) -&gt; { ... });            // appelé sur le thread réseau
 * t.send(bytes, hostIp, Hrp.PORT);
 * t.close();
 * </pre>
 */
public final class UdpTransport {

    private static final String TAG = "UdpTransport";
    /** Plus grand qu'un datagramme HRP (1 044 o) pour détecter les paquets trop gros. */
    private static final int RECEIVE_BUFFER = 2048;
    /** Tampon noyau : évite de perdre des paquets quand plusieurs transferts arrivent (#26). */
    private static final int SOCKET_BUFFER = 1 << 20;

    /** Reçoit chaque datagramme. Appelé sur le thread de réception, jamais sur le thread UI. */
    public interface Listener {
        void onPacket(byte[] data, InetAddress from, int port);
    }

    private final int requestedPort;
    private DatagramSocket socket;
    private Thread receiver;
    private ExecutorService sender;
    private volatile boolean running;

    /** @param port port local ; 0 = le système en choisit un (côté client). */
    public UdpTransport(int port) {
        this.requestedPort = port;
    }

    /** Ouvre le socket et démarre la boucle de réception. */
    public synchronized void start(Listener listener) throws SocketException {
        if (running) throw new IllegalStateException("déjà démarré");
        DatagramSocket s = new DatagramSocket(null);
        s.setReuseAddress(true);
        s.setReceiveBufferSize(SOCKET_BUFFER);
        s.setSendBufferSize(SOCKET_BUFFER);
        s.bind(new InetSocketAddress(requestedPort));
        socket = s;
        running = true;
        sender = Executors.newSingleThreadExecutor(r -> new Thread(r, "hrp-send"));
        receiver = new Thread(() -> receiveLoop(s, listener), "hrp-receive");
        receiver.start();
        Log.i(TAG, "écoute sur le port " + s.getLocalPort());
    }

    private void receiveLoop(DatagramSocket s, Listener listener) {
        byte[] buf = new byte[RECEIVE_BUFFER];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                packet.setLength(buf.length);
                s.receive(packet);
                byte[] data = Arrays.copyOf(buf, packet.getLength());
                try {
                    listener.onPacket(data, packet.getAddress(), packet.getPort());
                } catch (RuntimeException e) {
                    // Règle d'or : un paquet ne doit jamais tuer la boucle de réception.
                    Log.e(TAG, "erreur dans le listener", e);
                }
            } catch (IOException e) {
                if (running) Log.w(TAG, "réception : " + e.getMessage());
            }
        }
        Log.i(TAG, "boucle de réception terminée");
    }

    /** Envoie un datagramme en arrière-plan. Sans effet si le transport est fermé. */
    public void send(byte[] data, InetAddress ip, int port) {
        ExecutorService ex = sender;
        DatagramSocket s = socket;
        if (!running || ex == null || s == null) return;
        try {
            ex.execute(() -> {
                try {
                    s.send(new DatagramPacket(data, data.length, ip, port));
                } catch (IOException e) {
                    Log.w(TAG, "envoi vers " + ip.getHostAddress() + ":" + port + " : " + e.getMessage());
                }
            });
        } catch (RejectedExecutionException ignored) {
            // fermé entre-temps
        }
    }

    /** Port local réellement utilisé (utile quand on a demandé 0). */
    public int getLocalPort() {
        DatagramSocket s = socket;
        return s == null ? -1 : s.getLocalPort();
    }

    public boolean isRunning() {
        return running;
    }

    /** Ferme proprement : laisse partir les envois en attente, puis ferme le socket. */
    public synchronized void close() {
        if (!running) return;
        running = false;
        ExecutorService ex = sender;
        if (ex != null) {
            ex.shutdown();
            try {
                ex.awaitTermination(300, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (socket != null) socket.close(); // débloque receive()
        socket = null;
        sender = null;
        receiver = null;
    }
}
