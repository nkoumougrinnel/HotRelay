package com.example.hotrelay.session;

import com.example.hotrelay.protocol.Hrp;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Table de session de l'hôte (#14) : qui est connecté, à quelle adresse, depuis quand.
 *
 * <p>Plusieurs threads y accèdent (réseau, minuteries, interface) :
 * d'où le {@link ConcurrentHashMap} et l'ajout {@code synchronized}.</p>
 *
 * <p>Règles du protocole (§3, §8) : l'hôte a l'ID 0, les clients 1 à 7,
 * un ID n'est jamais réutilisé dans une même session.</p>
 */
public final class ParticipantRegistry {

    /** Plus grand ID client possible (8 participants hôte compris). */
    public static final int MAX_CLIENT_ID = Hrp.MAX_PARTICIPANTS - 1;

    /** Un client connecté. L'IP et le port ne sont jamais diffusés aux autres. */
    public static final class Entry {
        public final int id;
        public final String name;
        public final InetAddress address;
        public final int port;
        /** REQUEST_ID du NEW qui a créé ce participant (pour reconnaître les renvois). */
        public final long requestId;
        volatile long lastSeen;

        Entry(int id, String name, InetAddress address, int port, long requestId, long now) {
            this.id = id;
            this.name = name;
            this.address = address;
            this.port = port;
            this.requestId = requestId;
            this.lastSeen = now;
        }

        public long getLastSeen() { return lastSeen; }

        boolean isAt(InetAddress ip, int p) {
            return port == p && address.equals(ip);
        }

        @Override
        public String toString() {
            return id + ":" + name + "@" + address.getHostAddress() + ":" + port;
        }
    }

    private final ConcurrentHashMap<Integer, Entry> byId = new ConcurrentHashMap<>();
    private int nextId = 1;

    /**
     * Ajoute un client et lui attribue un nouvel ID.
     *
     * @return le participant créé, ou {@code null} si la session est pleine.
     */
    public synchronized Entry add(String name, InetAddress ip, int port, long requestId) {
        if (nextId > MAX_CLIENT_ID) return null; // IDs épuisés (jamais réutilisés)
        Entry e = new Entry(nextId++, name, ip, port, requestId, System.currentTimeMillis());
        byId.put(e.id, e);
        return e;
    }

    public Entry add(String name, InetAddress ip, int port) {
        return add(name, ip, port, 0);
    }

    public Entry remove(int id) {
        return byId.remove(id);
    }

    public Entry get(int id) {
        return byId.get(id);
    }

    /** Met à jour le dernier signe de vie. */
    public void touch(int id) {
        Entry e = byId.get(id);
        if (e != null) e.lastSeen = System.currentTimeMillis();
    }

    public Entry findByEndpoint(InetAddress ip, int port) {
        for (Entry e : byId.values()) {
            if (e.isAt(ip, port)) return e;
        }
        return null;
    }

    public Entry findByRequestId(long requestId) {
        for (Entry e : byId.values()) {
            if (e.requestId == requestId) return e;
        }
        return null;
    }

    /** Clients connectés, triés par ID. */
    public List<Entry> list() {
        List<Entry> l = new ArrayList<>(byId.values());
        Collections.sort(l, Comparator.comparingInt(e -> e.id));
        return l;
    }

    /** Clients silencieux depuis plus de {@code timeoutMs}. */
    public List<Entry> expired(long now, long timeoutMs) {
        List<Entry> l = new ArrayList<>();
        for (Entry e : byId.values()) {
            if (now - e.lastSeen > timeoutMs) l.add(e);
        }
        return l;
    }

    public int size() {
        return byId.size();
    }
}
