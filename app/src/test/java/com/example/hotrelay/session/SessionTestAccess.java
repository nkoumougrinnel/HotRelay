package com.example.hotrelay.session;

import java.net.InetAddress;

/** Donne aux tests d'autres packages accès aux constructeurs avec port personnalisé. */
public final class SessionTestAccess {

    private SessionTestAccess() {}

    public static HostSession newHost(String name, int port) {
        return new HostSession(name, port, p -> {});
    }

    public static ClientSession newClient(String name, InetAddress host, int port, ClientSession.Listener l) {
        return new ClientSession(name, host, port, l);
    }
}
