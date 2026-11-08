package com.example.hotrelay.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public class NetworkUtils {

    /**
     * Gets the current local IP address of the device (Wi-Fi or Hotspot interface).
     */
    public static String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                // Check Wi-Fi or Hotspot interfaces (wlan0, ap0, softap)
                if (intf.getName().contains("wlan") || intf.getName().contains("ap")) {
                    List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                    for (InetAddress addr : addrs) {
                        if (!addr.isLoopbackAddress()) {
                            String sAddr = addr.getHostAddress();
                            if (sAddr != null && sAddr.indexOf(':') < 0) { // IPv4
                                return sAddr;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "192.168.43.1"; // Default gateway IP for Android Hotspot
    }

    /**
     * Checks if Wi-Fi or Hotspot network is active.
     */
    public static boolean isNetworkActive(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnected();
        }
        return true;
    }
}