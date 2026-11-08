package com.example.hotrelay.model;

import java.io.Serializable;

public class Participant implements Serializable {

    private String id;
    private String name;
    private String ipAddress;
    private int port;
    private boolean isConnected;
    private boolean isHost;
    private int avatarColor;

    public Participant(String id, String name, String ipAddress, int port, boolean isConnected, boolean isHost) {
        this.id = id;
        this.name = name;
        this.ipAddress = ipAddress;
        this.port = port;
        this.isConnected = isConnected;
        this.isHost = isHost;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public int getPort() {
        return port;
    }

    public boolean isConnected() {
        return isConnected;
    }

    public void setConnected(boolean connected) {
        isConnected = connected;
    }

    public boolean isHost() {
        return isHost;
    }

    public void setHost(boolean host) {
        isHost = host;
    }

    public int getAvatarColor() {
        return avatarColor;
    }

    public void setAvatarColor(int avatarColor) {
        this.avatarColor = avatarColor;
    }
}