package com.njk.adskipper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HushVpnService
 * --------------
 * Local DNS filter. Only DNS traffic is routed into the tunnel (a single /32 route to a
 * virtual DNS address), so all other traffic keeps using the normal network untouched.
 * Runs as a foreground service with low-priority notification so it survives 30+ minutes
 * with the screen off and is never killed by Android's background power management.
 */
public class HushVpnService extends VpnService {

    private static final String TAG = "HushVpn";

    public static final String ACTION_START = "com.njk.adskipper.VPN_START";
    public static final String ACTION_STOP = "com.njk.adskipper.VPN_STOP";
    public static final String KEY_DNS_ENABLED = "dns_enabled";
    public static final String KEY_DNS_BLOCKED_TOTAL = "dns_blocked_total";

    private static final String CHANNEL_ID = "hush_vpn_channel";
    private static final int NOTIF_ID = 4040;

    private static final String VPN_ADDRESS = "10.111.222.1";
    private static final String VIRTUAL_DNS = "10.111.222.2";
    private static final String[] UPSTREAMS = {"1.1.1.1", "9.9.9.9", "8.8.8.8"};

    public static volatile boolean running = false;

    private ParcelFileDescriptor tun;
    private Thread worker;
    private ExecutorService pool;
    private volatile boolean stopping = false;
    private final Set<String> blocked = new HashSet<>();
    private final AtomicInteger blockedPending = new AtomicInteger(0);
    private FileOutputStream tunOut;

    public static void start(Context c) {
        Intent i = new Intent(c, HushVpnService.class).setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start HushVpnService", e);
        }
    }

    public static void stop(Context c) {
        Intent i = new Intent(c, HushVpnService.class).setAction(ACTION_STOP);
        try {
            c.startService(i);
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop HushVpnService", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            stopForegroundSafely();
            stopSelf();
            return START_NOT_STICKY;
        }

        startForegroundNotificationSafely();

        if (!running) {
            establish();
        }
        return START_STICKY;
    }

    private void stopForegroundSafely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Exception e) {
            Log.w(TAG, "stopForeground error", e);
        }
    }

    private void startForegroundNotificationSafely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel chan = new NotificationChannel(
                        CHANNEL_ID,
                        "Hush DNS Filter",
                        NotificationManager.IMPORTANCE_LOW
                );
                chan.setDescription("Maintains local on-device ad blocking");
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.createNotificationChannel(chan);
                }
            }

            Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_app_logo)
                    .setContentTitle("Hush Protection Active")
                    .setContentText("Local DNS ad filter running")
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .build();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, notif);
            }
        } catch (Throwable t) {
            // Never let notification permission or startForeground exception kill the app
            Log.e(TAG, "startForeground failed safely", t);
        }
    }

    private void establish() {
        loadBlocklist();
        try {
            Builder b = new Builder()
                    .setSession("Hush Local DNS")
                    .addAddress(VPN_ADDRESS, 32)
                    .addDnsServer(VIRTUAL_DNS)
                    .addRoute(VIRTUAL_DNS, 32)
                    .setBlocking(true);
            tun = b.establish();
        } catch (Exception e) {
            Log.e(TAG, "establish failed", e);
            tun = null;
        }
        if (tun == null) {
            setEnabledPref(false);
            stopForegroundSafely();
            stopSelf();
            return;
        }
        stopping = false;
        running = true;
        setEnabledPref(true);

        pool = Executors.newFixedThreadPool(4);
        tunOut = new FileOutputStream(tun.getFileDescriptor());

        worker = new Thread(this::packetLoop, "HushVpn-Worker");
        worker.start();
        Log.i(TAG, "Hush DNS VPN established with " + blocked.size() + " blocklist rules");
    }

    private void setEnabledPref(boolean on) {
        try {
            getSharedPreferences(AdSkipAccessibilityService.PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_DNS_ENABLED, on)
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "setEnabledPref error", e);
        }
    }

    private void loadBlocklist() {
        if (!blocked.isEmpty()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open("blocklist.txt")))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                blocked.add(line.toLowerCase(Locale.US));
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to load blocklist", e);
        }
    }

    private void packetLoop() {
        byte[] buf = new byte[4096];
        try (FileInputStream in = new FileInputStream(tun.getFileDescriptor())) {
            while (!stopping) {
                try {
                    int len = in.read(buf);
                    if (len <= 0) break;
                    if (!isIPv4(buf, len)) continue;
                    int ipHeaderLen = (buf[0] & 0x0F) * 4;
                    int proto = buf[9] & 0xFF;
                    if (proto != 17) continue; // UDP only
                    int udpOffset = ipHeaderLen;
                    if (len < udpOffset + 8) continue;
                    int srcPort = ((buf[udpOffset] & 0xFF) << 8) | (buf[udpOffset + 1] & 0xFF);
                    int dstPort = ((buf[udpOffset + 2] & 0xFF) << 8) | (buf[udpOffset + 3] & 0xFF);
                    if (dstPort != 53) continue;

                    int dnsOffset = udpOffset + 8;
                    int dnsLen = len - dnsOffset;
                    if (dnsLen < 12) continue;

                    byte[] queryData = new byte[dnsLen];
                    System.arraycopy(buf, dnsOffset, queryData, 0, dnsLen);

                    byte[] ipSrc = new byte[4];
                    byte[] ipDst = new byte[4];
                    System.arraycopy(buf, 12, ipSrc, 0, 4);
                    System.arraycopy(buf, 16, ipDst, 0, 4);

                    if (pool != null && !stopping) {
                        pool.execute(() -> {
                            try {
                                handleQuery(queryData, ipSrc, ipDst, srcPort, dstPort);
                            } catch (Throwable t) {
                                Log.w(TAG, "handleQuery pool error", t);
                            }
                        });
                    }
                } catch (Throwable t) {
                    if (stopping) break;
                    Log.w(TAG, "packetLoop inner packet error", t);
                }
            }
        } catch (Throwable e) {
            if (!stopping) Log.e(TAG, "packetLoop read error", e);
        } finally {
            shutdown();
        }
    }

    private boolean isIPv4(byte[] b, int len) {
        return len >= 20 && ((b[0] >> 4) & 0x0F) == 4;
    }

    private void handleQuery(byte[] query, byte[] clientIp, byte[] dnsServerIp, int clientPort, int dnsPort) {
        try {
            String domain = parseQuestionName(query);
            boolean shouldBlock = domain != null && isBlocked(domain);

            byte[] dnsResponse;
            if (shouldBlock) {
                dnsResponse = buildSinkholeResponse(query);
                recordBlock();
            } else {
                dnsResponse = forwardQuery(query);
            }
            if (dnsResponse == null) return;

            byte[] packet = buildUdpPacket(dnsServerIp, clientIp, dnsPort, clientPort, dnsResponse);
            synchronized (this) {
                if (tunOut != null && !stopping) {
                    try {
                        tunOut.write(packet);
                    } catch (IOException e) {
                        Log.w(TAG, "tun write error", e);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "handleQuery error", t);
        }
    }

    private boolean isBlocked(String d) {
        if (d == null) return false;
        String cur = d.toLowerCase(Locale.US);
        while (true) {
            // Never match a bare TLD (e.g. "com", "net")
            if (cur.indexOf('.') < 0) break;
            if (blocked.contains(cur)) return true;
            int dot = cur.indexOf('.');
            if (dot < 0) break;
            cur = cur.substring(dot + 1);
        }
        return false;
    }

    private String parseQuestionName(byte[] q) {
        if (q == null || q.length < 12) return null;
        StringBuilder sb = new StringBuilder();
        int i = 12;
        while (i < q.length) {
            int len = q[i] & 0xFF;
            if (len == 0) break;
            if ((len & 0xC0) != 0) return null; // Compression in query question is invalid
            i++;
            if (i + len > q.length) return null;
            if (sb.length() > 0) sb.append('.');
            for (int j = 0; j < len; j++) {
                sb.append((char) q[i + j]);
            }
            i += len;
        }
        return sb.toString();
    }

    private byte[] buildSinkholeResponse(byte[] q) {
        if (q == null || q.length < 17) return null;
        int i = 12;
        while (i < q.length && q[i] != 0) i += (q[i] & 0xFF) + 1;
        int qEnd = i + 5; // zero byte + QTYPE (2) + QCLASS (2)
        if (qEnd > q.length) return null;
        int qtype = ((q[i + 1] & 0xFF) << 8) | (q[i + 2] & 0xFF);
        boolean a = (qtype == 1);       // A query -> 0.0.0.0 (4 bytes)
        boolean aaaa = (qtype == 28);   // AAAA query -> :: (16 bytes)

        // Answers only by query type. Anything else -> NXDOMAIN with 0 answers.
        int rdLen = a ? 4 : (aaaa ? 16 : 0);
        int ansLen = (a || aaaa) ? 12 + rdLen : 0;

        byte[] r = new byte[qEnd + ansLen];
        System.arraycopy(q, 0, r, 0, qEnd);
        r[2] = (byte) (0x80 | (q[2] & 0x01));             // QR=1, keep RD
        r[3] = (byte) (0x80 | ((a || aaaa) ? 0 : 3));     // RA=1, RCODE 0 or 3 (NXDOMAIN)
        r[6] = 0;
        r[7] = (byte) ((a || aaaa) ? 1 : 0);              // ANCOUNT = 1 or 0
        r[8] = 0; r[9] = 0; r[10] = 0; r[11] = 0;         // NSCOUNT, ARCOUNT = 0
        if (a || aaaa) {
            int o = qEnd;
            r[o++] = (byte) 0xC0; r[o++] = 0x0C;          // pointer to question name
            r[o++] = (byte) (qtype >> 8);
            r[o++] = (byte) qtype;                        // TYPE matches query exactly
            r[o++] = 0; r[o++] = 1;                       // CLASS IN
            r[o++] = 0; r[o++] = 0; r[o++] = 0; r[o++] = 60; // TTL = 60s
            r[o++] = 0; r[o] = (byte) rdLen;              // RDLENGTH; RDATA stays all zeros (0.0.0.0 or ::)
        }
        return r;
    }

    private byte[] forwardQuery(byte[] q) {
        for (String upstream : UPSTREAMS) {
            try (DatagramSocket sock = new DatagramSocket()) {
                protect(sock);
                sock.setSoTimeout(1500);
                InetAddress addr = InetAddress.getByName(upstream);
                DatagramPacket out = new DatagramPacket(q, q.length, addr, 53);
                sock.send(out);
                byte[] inBuf = new byte[4096];
                DatagramPacket in = new DatagramPacket(inBuf, inBuf.length);
                sock.receive(in);
                byte[] r = new byte[in.getLength()];
                System.arraycopy(inBuf, 0, r, 0, in.getLength());
                return r;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private byte[] buildUdpPacket(byte[] srcIp, byte[] dstIp, int srcPort, int dstPort, byte[] payload) {
        int ipLen = 20 + 8 + payload.length;
        byte[] pkt = new byte[ipLen];
        pkt[0] = 0x45;
        pkt[1] = 0x00;
        pkt[2] = (byte) (ipLen >> 8);
        pkt[3] = (byte) ipLen;
        pkt[4] = 0;
        pkt[5] = 0;
        pkt[6] = 0x40;
        pkt[7] = 0;
        pkt[8] = 64;
        pkt[9] = 17;
        pkt[10] = 0;
        pkt[11] = 0;
        System.arraycopy(srcIp, 0, pkt, 12, 4);
        System.arraycopy(dstIp, 0, pkt, 16, 4);
        int csum = ipChecksum(pkt, 0, 20);
        pkt[10] = (byte) (csum >> 8);
        pkt[11] = (byte) csum;

        int udpLen = 8 + payload.length;
        pkt[20] = (byte) (srcPort >> 8);
        pkt[21] = (byte) srcPort;
        pkt[22] = (byte) (dstPort >> 8);
        pkt[23] = (byte) dstPort;
        pkt[24] = (byte) (udpLen >> 8);
        pkt[25] = (byte) udpLen;
        pkt[26] = 0;
        pkt[27] = 0;
        System.arraycopy(payload, 0, pkt, 28, payload.length);
        return pkt;
    }

    private int ipChecksum(byte[] b, int off, int len) {
        int sum = 0;
        for (int i = 0; i < len; i += 2) {
            int w = ((b[off + i] & 0xFF) << 8) | (b[off + i + 1] & 0xFF);
            sum += w;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return ~sum & 0xFFFF;
    }

    private void recordBlock() {
        int p = blockedPending.incrementAndGet();
        if (p >= 5) {
            flushBlockedCounter();
        }
    }

    private synchronized void flushBlockedCounter() {
        int add = blockedPending.getAndSet(0);
        if (add <= 0) return;
        try {
            SharedPreferences sp = getSharedPreferences(AdSkipAccessibilityService.PREFS_NAME, MODE_PRIVATE);
            int total = sp.getInt(KEY_DNS_BLOCKED_TOTAL, 0) + add;
            sp.edit().putInt(KEY_DNS_BLOCKED_TOTAL, total).apply();
        } catch (Exception e) {
            Log.e(TAG, "flushBlockedCounter error", e);
        }
    }

    private synchronized void shutdown() {
        if (stopping) return;
        stopping = true;
        running = false;
        flushBlockedCounter();
        setEnabledPref(false);
        if (pool != null) {
            try { pool.shutdownNow(); } catch (Exception ignored) {}
            pool = null;
        }
        if (tunOut != null) {
            try { tunOut.close(); } catch (Exception ignored) {}
            tunOut = null;
        }
        if (tun != null) {
            try { tun.close(); } catch (Exception ignored) {}
            tun = null;
        }
        stopForegroundSafely();
        Log.i(TAG, "Hush DNS VPN shut down completely");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        shutdown();
    }

    @Override
    public void onRevoke() {
        super.onRevoke();
        shutdown();
    }
}
