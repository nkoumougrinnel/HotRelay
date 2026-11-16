package com.example.hotrelay.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;

/**
 * Adresses IP utiles à HotRelay (#13).
 *
 * <ul>
 *   <li>Client : {@link #getGatewayIp} = IP de l'hôte (la passerelle du Wi-Fi du hotspot),
 *       pour pré-remplir le champ. L'utilisateur peut toujours la corriger (protocole §2).</li>
 *   <li>Hôte : {@link #getHotspotIp} = IP à afficher sur l'écran hôte.</li>
 * </ul>
 * Depuis Android 10, l'adresse n'est pas toujours 192.168.43.1 : on la lit sur le système.
 */
public final class NetworkUtils {

    private static final String TAG = "NetworkUtils";

    private NetworkUtils() {}

    /**
     * IP de la passerelle du réseau Wi-Fi auquel on est connecté, ou {@code null}.
     * Méthode 1 : routes du réseau Wi-Fi (fiable sur Android récents).
     * Méthode 2 : DhcpInfo (ancienne API, encore utile sur certains téléphones).
     */
    public static InetAddress getGatewayIp(Context context) {
        InetAddress fromRoutes = gatewayFromRoutes(context);
        if (fromRoutes != null) return fromRoutes;
        return gatewayFromDhcp(context);
    }

    private static InetAddress gatewayFromRoutes(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        // On cherche le réseau Wi-Fi, pas forcément le réseau « actif »
        // (sans Internet, Android peut préférer les données mobiles).
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) continue;
            for (RouteInfo route : lp.getRoutes()) {
                InetAddress gw = route.getGateway();
                if (route.isDefaultRoute() && gw instanceof Inet4Address && !gw.isAnyLocalAddress()) {
                    return gw;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static InetAddress gatewayFromDhcp(Context context) {
        WifiManager wm = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return null;
        DhcpInfo info = wm.getDhcpInfo();
        if (info == null || info.gateway == 0) return null;
        return intToInet(info.gateway);
    }

    /** DhcpInfo stocke l'IP en little-endian : 0x0101A8C0 = 192.168.1.1. */
    static InetAddress intToInet(int ip) {
        byte[] b = {
                (byte) (ip & 0xFF),
                (byte) ((ip >> 8) & 0xFF),
                (byte) ((ip >> 16) & 0xFF),
                (byte) ((ip >> 24) & 0xFF)
        };
        try {
            return InetAddress.getByAddress(b);
        } catch (UnknownHostException e) {
            return null; // impossible avec 4 octets
        }
    }

    /**
     * IP de ce téléphone sur l'interface du hotspot (côté hôte), ou {@code null}.
     * Les noms d'interface varient selon les marques : ap0, swlan0, wlan1, softap0…
     */
    public static InetAddress getHotspotIp() {
        InetAddress fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName().toLowerCase();
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    if (name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap")) {
                        return a; // interface hotspot sûre
                    }
                    if (name.startsWith("wlan") && fallback == null) fallback = a;
                }
            }
        } catch (SocketException e) {
            Log.w(TAG, "interfaces : " + e.getMessage());
        }
        return fallback;
    }

    /** Lit une IP tapée par l'utilisateur ; {@code null} si ce n'est pas une IPv4 valide. */
    public static InetAddress parseIpv4(String text) {
        if (text == null) return null;
        String[] parts = text.trim().split("\\.");
        if (parts.length != 4) return null;
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) {
            try {
                int v = Integer.parseInt(parts[i]);
                if (v < 0 || v > 255) return null;
                b[i] = (byte) v;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            return InetAddress.getByAddress(b);
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
