package com.extbridge.helper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Self-contained on-device ADB helper bundling native arm64-v8a adb (libadb.so),
 * NsdManager mDNS discovery for Wireless Debugging pairing/connect endpoints,
 * and RemoteInput notification support for typing the 6-digit pairing code
 * without dismissing Android's pairing popup.
 */
public final class AdbBootstrap {
    private static final String TAG = "ExtBridgeAdb";
    public static final String ACTION_PAIR_REPLY = "com.extbridge.helper.ACTION_PAIR_REPLY";
    public static final String ACTION_STOP_PAIR_NOTIF = "com.extbridge.helper.ACTION_STOP_PAIR_NOTIF";
    public static final String KEY_PAIR_INPUT = "pair_input";
    private static final String PAIR_CHANNEL_ID = "extbridge_adb_pair";
    private static final int PAIR_NOTIF_ID = 3901;
    private static final String SERVICE_PAIRING = "_adb-tls-pairing._tcp.";
    private static final String SERVICE_CONNECT = "_adb-tls-connect._tcp.";
    private static final String PREFS_NAME = "extbridge_prefs";
    private static final String KEY_AUTO_ROOT = "auto_root_enabled";
    private static final String KEY_AUTO_CLOSE_WIFI_ADB = "auto_close_wifi_adb";
    private static String sActivePairSvcName = "";
    private static String sActiveConnSvcName = "";

    private static final ExecutorService sExec = Executors.newSingleThreadExecutor();
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    public static final class DiscoveryState {
        public String pairingHost = "";
        public int pairingPort = 0;
        public String connectHost = "";
        public int connectPort = 0;
    }

    public interface DiscoveryListener {
        void onDiscoveryUpdated(DiscoveryState state);
    }

    public interface ProgressListener {
        void onStep(String message);
        void onResult(boolean success, String message);
    }

    private static NsdManager sNsd;
    private static NsdManager.DiscoveryListener sPairDisc;
    private static NsdManager.DiscoveryListener sConnDisc;
    private static WifiManager.MulticastLock sMcastLock;
    private static final DiscoveryState sDiscState = new DiscoveryState();
    private static final ArrayDeque<NsdServiceInfo> sResolveQueue = new ArrayDeque<>();
    private static boolean sResolving = false;
    private static DiscoveryListener sDiscListener;
    private static volatile boolean sPairNotifShowing = false;

    private AdbBootstrap() {}

    public static synchronized DiscoveryState getDiscoveryState() {
        DiscoveryState copy = new DiscoveryState();
        copy.pairingHost = sDiscState.pairingHost;
        copy.pairingPort = sDiscState.pairingPort;
        copy.connectHost = sDiscState.connectHost;
        copy.connectPort = sDiscState.connectPort;
        return copy;
    }

    public static synchronized void startDiscovery(Context ctx, DiscoveryListener listener) {
        sDiscListener = listener;
        Context appCtx = ctx.getApplicationContext();
        if (sNsd != null) {
            notifyDiscListener();
            return;
        }
        try {
            WifiManager wm = (WifiManager) appCtx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null && sMcastLock == null) {
                sMcastLock = wm.createMulticastLock("extbridge_mdns");
                sMcastLock.setReferenceCounted(false);
                sMcastLock.acquire();
            }
        } catch (Throwable t) {
            Log.w(TAG, "MulticastLock unavailable", t);
        }

        try {
            sNsd = (NsdManager) appCtx.getSystemService(Context.NSD_SERVICE);
            if (sNsd == null) return;
            sPairDisc = makeDiscoveryListener(true);
            sConnDisc = makeDiscoveryListener(false);
            sNsd.discoverServices(SERVICE_PAIRING, NsdManager.PROTOCOL_DNS_SD, sPairDisc);
            sNsd.discoverServices(SERVICE_CONNECT, NsdManager.PROTOCOL_DNS_SD, sConnDisc);
        } catch (Throwable t) {
            Log.e(TAG, "startDiscovery failed", t);
        }
    }

    public static synchronized void stopDiscoveryIfIdle() {
        if (sPairNotifShowing) {
            sDiscListener = null;
            return;
        }
        stopDiscovery();
    }

    public static synchronized void stopDiscovery() {
        sDiscListener = null;
        if (sNsd != null) {
            try { if (sPairDisc != null) sNsd.stopServiceDiscovery(sPairDisc); } catch (Throwable ignored) {}
            try { if (sConnDisc != null) sNsd.stopServiceDiscovery(sConnDisc); } catch (Throwable ignored) {}
        }
        sPairDisc = null;
        sConnDisc = null;
        sNsd = null;
        synchronized (sResolveQueue) {
            sResolveQueue.clear();
            sResolving = false;
        }
        if (sMcastLock != null) {
            try { if (sMcastLock.isHeld()) sMcastLock.release(); } catch (Throwable ignored) {}
            sMcastLock = null;
        }
    }

    private static NsdManager.DiscoveryListener makeDiscoveryListener(final boolean isPairing) {
        return new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String regType) {}
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {}
            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {}
            @Override public void onDiscoveryStopped(String serviceType) {}

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                enqueueResolve(serviceInfo);
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                String lostName = (serviceInfo != null && serviceInfo.getServiceName() != null)
                        ? serviceInfo.getServiceName() : "";
                synchronized (AdbBootstrap.class) {
                    if (isPairing) {
                        if (lostName.isEmpty() || sActivePairSvcName.isEmpty() || lostName.equals(sActivePairSvcName)) {
                            sDiscState.pairingPort = 0;
                            sActivePairSvcName = "";
                            notifyDiscListener();
                        }
                    } else {
                        if (lostName.isEmpty() || sActiveConnSvcName.isEmpty() || lostName.equals(sActiveConnSvcName)) {
                            sDiscState.connectPort = 0;
                            sActiveConnSvcName = "";
                            notifyDiscListener();
                        }
                    }
                }
            }
        };
    }

    private static void enqueueResolve(NsdServiceInfo info) {
        synchronized (sResolveQueue) {
            sResolveQueue.addLast(info);
            pumpResolveLocked();
        }
    }

    @SuppressWarnings("deprecation")
    private static void pumpResolveLocked() {
        if (sResolving || sResolveQueue.isEmpty() || sNsd == null) return;
        final NsdServiceInfo next = sResolveQueue.pollFirst();
        if (next == null) return;
        sResolving = true;
        try {
            sNsd.resolveService(next, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    synchronized (sResolveQueue) {
                        sResolving = false;
                        pumpResolveLocked();
                    }
                }

                @Override
                public void onServiceResolved(NsdServiceInfo resolved) {
                    handleResolved(resolved);
                    synchronized (sResolveQueue) {
                        sResolving = false;
                        pumpResolveLocked();
                    }
                }
            });
        } catch (Throwable t) {
            sResolving = false;
            pumpResolveLocked();
        }
    }

    private static boolean isLocalDeviceAddress(InetAddress addr) {
        if (addr == null) return false;
        if (addr.isLoopbackAddress()) return true;
        try {
            if (NetworkInterface.getByInetAddress(addr) != null) return true;
        } catch (Throwable ignored) {}
        String ip = addr.getHostAddress();
        if (ip != null && ip.contains("%")) {
            ip = ip.substring(0, ip.indexOf('%'));
        }
        return ip != null && ip.equals(getLocalIpv4());
    }

    private static boolean isAllowedLocalHost(String rawHost) {
        if (rawHost == null) return false;
        String h = rawHost.trim();
        if (h.startsWith("[") && h.endsWith("]") && h.length() > 2) {
            h = h.substring(1, h.length() - 1);
        }
        if (h.contains("%")) {
            h = h.substring(0, h.indexOf('%'));
        }
        if (h.isEmpty()) return false;
        if ("127.0.0.1".equals(h) || "localhost".equalsIgnoreCase(h) || "::1".equals(h)) {
            return true;
        }
        // Only parse numeric IPv4/IPv6 literals so crafted strings never trigger external DNS resolution
        if (!h.matches("^[0-9a-fA-F.:]+$")) {
            return false;
        }
        try {
            return isLocalDeviceAddress(InetAddress.getByName(h));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void handleResolved(NsdServiceInfo resolved) {
        String type = resolved.getServiceType();
        if (type == null) type = "";
        InetAddress addr = resolved.getHost();
        if (!isLocalDeviceAddress(addr)) return;
        String host = addr != null ? addr.getHostAddress() : "";
        if (host != null && host.contains("%")) {
            host = host.substring(0, host.indexOf('%'));
        }
        if (host != null && host.contains(":") && !host.startsWith("[")) {
            // Prefer local IPv4 for adb connect/pair when link-local IPv6 scope was stripped
            host = getLocalIpv4();
        }
        int port = resolved.getPort();
        if (port <= 0) return;
        String svcName = resolved.getServiceName() != null ? resolved.getServiceName() : "";

        synchronized (AdbBootstrap.class) {
            if (type.contains("pairing")) {
                sDiscState.pairingHost = host != null ? host : "";
                sDiscState.pairingPort = port;
                sActivePairSvcName = svcName;
            } else if (type.contains("connect")) {
                sDiscState.connectHost = host != null ? host : "";
                sDiscState.connectPort = port;
                sActiveConnSvcName = svcName;
            }
            notifyDiscListener();
        }
    }

    private static void notifyDiscListener() {
        final DiscoveryListener l = sDiscListener;
        if (l == null) return;
        final DiscoveryState snap = getDiscoveryState();
        sMain.post(() -> l.onDiscoveryUpdated(snap));
    }

    public static String getLocalIpv4() {
        String best = null;
        String fallback = null;
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface nif = ifaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                String name = nif.getName() == null ? "" : nif.getName().toLowerCase();
                if (name.startsWith("tun") || name.startsWith("utun") || name.startsWith("ppp")
                        || name.startsWith("dummy")) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String ip = a.getHostAddress();
                        if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("eth")) {
                            return ip;
                        }
                        if (!name.startsWith("rmnet") && !name.startsWith("ccmni")) {
                            if (best == null) best = ip;
                        } else if (fallback == null) {
                            fallback = ip;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (best != null) return best;
        if (fallback != null) return fallback;
        return "127.0.0.1";
    }

    public static String buildPcAdbCommand() {
        // Zero '$', zero backticks, zero escaped inner quotes, zero Toybox-incompatible `unzip -t`:
        // runs identically in Bash, Zsh, sh, PowerShell, and cmd.exe across Android 11-16!
        return "adb shell \"rm -f /data/local/tmp/extb_src.apk /data/local/tmp/extbridge.jar.new && "
                + "pm path com.extbridge.helper | grep '^package:' | cut -d: -f2 | head -n1 | xargs cat > /data/local/tmp/extb_src.apk && "
                + "test -s /data/local/tmp/extb_src.apk && "
                + "unzip -p /data/local/tmp/extb_src.apk assets/extbridge.jar > /data/local/tmp/extbridge.jar.new && "
                + "rm -f /data/local/tmp/extb_src.apk && "
                + "test -s /data/local/tmp/extbridge.jar.new && "
                + "chmod 644 /data/local/tmp/extbridge.jar.new && "
                + "timeout 6 env CLASSPATH=/data/local/tmp/extbridge.jar.new app_process /data/local/tmp com.extbridge.daemon.ExtBridge --verify-helper >/dev/null 2>&1 && "
                + "(timeout 8 env CLASSPATH=/data/local/tmp/extbridge.jar.new app_process /data/local/tmp com.extbridge.daemon.ExtBridge --stop >/dev/null 2>&1 || true) && "
                + "((pgrep -f '^app_process.*ExtBridge' >/dev/null 2>&1 || pgrep -f '^/system/bin/app_process.*ExtBridge' >/dev/null 2>&1) && (pkill -f '^app_process.*ExtBridge' 2>/dev/null; pkill -f '^/system/bin/app_process.*ExtBridge' 2>/dev/null; true) && sleep 1 && (pkill -f -l KILL '^app_process.*ExtBridge' 2>/dev/null; pkill -f -l KILL '^/system/bin/app_process.*ExtBridge' 2>/dev/null; true) || true) && "
                + "rm -f /data/local/tmp/extbridge.jar && "
                + "mv -f /data/local/tmp/extbridge.jar.new /data/local/tmp/extbridge.jar && "
                + "test -s /data/local/tmp/extbridge.jar && "
                + "(appops set com.extbridge.helper SYSTEM_ALERT_WINDOW allow 2>/dev/null || true) && "
                + "(appops set com.extbridge.helper RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true) && "
                + "(appops set com.extbridge.helper 10021 allow 2>/dev/null || true) && "
                + "(appops set com.extbridge.helper ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null || true) && "
                + "(pm grant com.extbridge.helper android.permission.POST_NOTIFICATIONS 2>/dev/null || true) && "
                + "(dumpsys deviceidle whitelist +com.extbridge.helper >/dev/null 2>&1 || true) && "
                + "(CLASSPATH=/data/local/tmp/extbridge.jar nohup app_process /data/local/tmp com.extbridge.daemon.ExtBridge >/data/local/tmp/extbridge.log 2>&1 </dev/null &)\"";
    }

    private static File getPrivateAdbSocketFile(Context ctx) {
        File sockDir = new File(ctx.getCacheDir(), "asrv");
        if (!sockDir.exists()) {
            sockDir.mkdirs();
        }
        sockDir.setReadable(false, false);
        sockDir.setReadable(true, true);
        sockDir.setWritable(false, false);
        sockDir.setWritable(true, true);
        sockDir.setExecutable(false, false);
        sockDir.setExecutable(true, true);
        return new File(sockDir, "a.sock");
    }

    private static void stopLocalAdbServerSync(Context appCtx) {
        if (appCtx == null) return;
        if (isNativeAdbAvailable(appCtx)) {
            try { runAdb(appCtx, 1500, "disconnect"); } catch (Throwable ignored) {}
            try { runAdb(appCtx, 2500, "kill-server"); } catch (Throwable ignored) {}
        }
        try {
            File sockFile = getPrivateAdbSocketFile(appCtx);
            if (sockFile.exists()) sockFile.delete();
        } catch (Throwable ignored) {}
    }

    public static void killLocalAdbServerAsync(final Context ctx) {
        if (ctx == null) return;
        final Context appCtx = ctx.getApplicationContext();
        new Thread(() -> stopLocalAdbServerSync(appCtx), "ExtAdbStop").start();
    }

    public static boolean isNativeAdbAvailable(Context ctx) {
        File f = getAdbFile(ctx);
        return f.exists() && f.canExecute();
    }

    private static File getAdbFile(Context ctx) {
        return new File(ctx.getApplicationInfo().nativeLibraryDir, "libadb.so");
    }

    public static void pairAndBootstrapAsync(
            final Context ctx,
            final String pairHost,
            final int pairPort,
            final String pairCode,
            final String connectHost,
            final int connectPort,
            final ProgressListener listener) {
        final Context appCtx = ctx.getApplicationContext();
        sExec.execute(() -> {
            try {
                pairAndBootstrapSync(appCtx, pairHost, pairPort, pairCode, connectHost, connectPort, listener);
            } catch (Throwable t) {
                Log.e(TAG, "pairAndBootstrap failed", t);
                postResult(listener, false, "执行异常: " + t.getMessage());
            }
        });
    }

    public static void connectAndBootstrapAsync(
            final Context ctx,
            final String connectHost,
            final int connectPort,
            final ProgressListener listener) {
        final Context appCtx = ctx.getApplicationContext();
        sExec.execute(() -> {
            try {
                connectAndBootstrapSync(appCtx, connectHost, connectPort, listener);
            } catch (Throwable t) {
                Log.e(TAG, "connectAndBootstrap failed", t);
                postResult(listener, false, "执行异常: " + t.getMessage());
            }
        });
    }

    public static void pairFromNotificationAsync(
            final Context ctx,
            final String rawInput,
            final ProgressListener listener) {
        final Context appCtx = ctx.getApplicationContext();
        sExec.execute(() -> {
            DiscoveryState ds = getDiscoveryState();
            String cleaned = rawInput == null ? "" : rawInput.trim().replace('：', ':');
            String code = "";
            String host = !ds.pairingHost.isEmpty() ? ds.pairingHost : getLocalIpv4();
            int pairPort = ds.pairingPort;

            String[] parts = cleaned.split("[\\s:]+");
            if (parts.length == 1) {
                code = parts[0];
            } else if (parts.length == 2) {
                // Either "CODE PORT" or "PORT CODE"
                if (parts[0].length() == 6 && parts[1].length() != 6) {
                    code = parts[0];
                    pairPort = parseIntSafe(parts[1], pairPort);
                } else {
                    pairPort = parseIntSafe(parts[0], pairPort);
                    code = parts[1];
                }
            } else if (parts.length >= 3) {
                host = parts[0];
                pairPort = parseIntSafe(parts[1], pairPort);
                code = parts[2];
            }

            if (code.isEmpty()) {
                postResult(listener, false, "请输入 6 位配对码");
                return;
            }
            if (!isAllowedLocalHost(host)) {
                postResult(listener, false, "安全拦截：配对地址仅允许本机 IP（拒绝外部地址 " + host + "）");
                return;
            }
            if (pairPort <= 0 || pairPort > 65535) {
                postResult(listener, false, "未自动发现配对端口，请按「配对码:端口」输入（例如 551857:36565）");
                return;
            }
            pairAndBootstrapSync(appCtx, host, pairPort, code, ds.connectHost, ds.connectPort, listener);
        });
    }

    private static void pairAndBootstrapSync(
            Context appCtx,
            String pairHost,
            int pairPort,
            String pairCode,
            String connectHost,
            int connectPort,
            ProgressListener listener) {
        if (!isNativeAdbAvailable(appCtx)) {
            postResult(listener, false, "内置 ADB 模块 (libadb.so) 不可用");
            return;
        }
        String pHost = (pairHost == null || pairHost.trim().isEmpty()) ? getLocalIpv4() : pairHost.trim();
        if (!isAllowedLocalHost(pHost)) {
            postResult(listener, false, "安全拦截：配对主机必须是本机回环或本机局域网 IP");
            return;
        }
        String code = pairCode == null ? "" : pairCode.trim();
        if (pairPort <= 0 || pairPort > 65535 || code.isEmpty()) {
            postResult(listener, false, "请填写完整的配对端口和 6 位配对码");
            return;
        }

        try {
            postStep(listener, "正在启动内置 ADB 服务...");
            CmdRes startRes = runAdb(appCtx, 10000, "start-server");
            if (startRes.code != 0) {
                postResult(listener, false, "ADB 服务启动失败:\n" + startRes.output);
                return;
            }

            String pairTarget = pHost + ":" + pairPort;
            postStep(listener, "正在与 " + pairTarget + " 配对...");
            CmdRes pairRes = runAdb(appCtx, 15000, "pair", pairTarget, code);
            String pOut = pairRes.output == null ? "" : pairRes.output.trim();
            boolean pairOk = pairRes.code == 0 && !pOut.toLowerCase().contains("failed") && !pOut.toLowerCase().contains("error");
            if (!pairOk) {
                postResult(listener, false, "配对失败 (" + pairTarget + "):\n" + pOut
                        + "\n提示：请确保没有关闭系统的「与设备配对」弹窗导致配对码刷新。");
                return;
            }

            postStep(listener, "配对成功！正在等待无线调试连接端口...");
            String cHost = (connectHost != null && !connectHost.trim().isEmpty()) ? connectHost.trim() : pHost;
            int cPort = connectPort;
            if (cPort <= 0) {
                long deadline = System.currentTimeMillis() + 4500;
                while (System.currentTimeMillis() < deadline) {
                    DiscoveryState ds = getDiscoveryState();
                    if (ds.connectPort > 0) {
                        cPort = ds.connectPort;
                        if (!ds.connectHost.isEmpty()) cHost = ds.connectHost;
                        break;
                    }
                    try { Thread.sleep(250); } catch (InterruptedException ignored) { break; }
                }
            }
            if (cPort <= 0) {
                postResult(listener, true, "已成功完成设备配对！请关闭系统的配对弹窗，输入无线调试主界面显示的连接端口后点击「直接连接并启动」。");
                return;
            }
            connectAndBootstrapCore(appCtx, cHost, cPort, listener, false);
        } finally {
            stopLocalAdbServerSync(appCtx);
        }
    }

    private static void connectAndBootstrapSync(
            Context appCtx,
            String connectHost,
            int connectPort,
            ProgressListener listener) {
        if (!isNativeAdbAvailable(appCtx)) {
            postResult(listener, false, "内置 ADB 模块 (libadb.so) 不可用");
            return;
        }
        if (connectPort <= 0 || connectPort > 65535) {
            postResult(listener, false, "请填写无线调试主页显示的端口号");
            return;
        }
        try {
            connectAndBootstrapCore(appCtx, connectHost, connectPort, listener, true);
        } finally {
            stopLocalAdbServerSync(appCtx);
        }
    }

    private static void connectAndBootstrapCore(
            Context appCtx,
            String connectHost,
            int connectPort,
            ProgressListener listener,
            boolean needStartServer) {
        String host = (connectHost == null || connectHost.trim().isEmpty()) ? getLocalIpv4() : connectHost.trim();
        if (!isAllowedLocalHost(host)) {
            postResult(listener, false, "安全拦截：无线调试连接地址仅允许本机 IP");
            return;
        }
        String target = host + ":" + connectPort;

        postStep(listener, "正在连接无线调试 " + target + "...");
        if (needStartServer) {
            CmdRes startRes = runAdb(appCtx, 8000, "start-server");
            if (startRes.code != 0) {
                postResult(listener, false, "ADB 服务启动失败:\n" + startRes.output);
                return;
            }
        }
        CmdRes connRes = runAdb(appCtx, 12000, "connect", target);
        String cOut = connRes.output == null ? "" : connRes.output.trim();
        String cLow = cOut.toLowerCase();
        boolean connOk = (cLow.contains("connected to") || cLow.contains("already connected"))
                && !cLow.contains("failed") && !cLow.contains("cannot connect") && !cLow.contains("unable to connect");
        if (!connOk && !"127.0.0.1".equals(host)) {
            String fbTarget = "127.0.0.1:" + connectPort;
            CmdRes fbRes = runAdb(appCtx, 10000, "connect", fbTarget);
            String fbOut = fbRes.output == null ? "" : fbRes.output.trim();
            String fbLow = fbOut.toLowerCase();
            boolean fbOk = (fbLow.contains("connected to") || fbLow.contains("already connected"))
                    && !fbLow.contains("failed") && !fbLow.contains("cannot connect") && !fbLow.contains("unable to connect");
            if (fbOk) {
                target = fbTarget;
                connOk = true;
            }
        }
        if (!connOk) {
            postResult(listener, false, "连接无线调试失败 (" + target + "):\n" + cOut
                    + "\n若为新设备，请先用「配对码」完成配对并确认连接端口已更新。");
            return;
        }

        postStep(listener, "已连接！正在释放 extbridge.jar 并授权启动守护进程...");
        String[] staged = stageBridgeJarWithSha256(appCtx);
        String localStagePath = (staged != null) ? staged[0] : null;
        String expectedSha = (staged != null && staged[1] != null) ? staged[1] : computeAssetJarSha256(appCtx);
        String remoteTmp = "/data/local/tmp/extb_" + randomHex16() + ".jar";
        boolean pushedOk = false;

        if (localStagePath != null) {
            File stageFile = new File(localStagePath);
            if (stageFile.isFile() && stageFile.length() > 0) {
                // Tier 1: Direct `adb push` via SYNC protocol — completely bypasses `pm path`, `/data/app` SELinux,
                // Xiaomi Second Space / Split APKs, and Toybox `unzip` quirks!
                CmdRes pushRes = runAdb(appCtx, 15000, "-s", target, "push", localStagePath, remoteTmp);
                if (pushRes.code == 0) {
                    pushedOk = true;
                } else {
                    // Tier 2: Raw stdin stream into `cat > remoteTmp`
                    CmdRes pipeRes = runAdbWithStdinFile(
                            appCtx,
                            15000,
                            stageFile,
                            "-s", target, "shell",
                            "rm -f '" + remoteTmp + "' 2>/dev/null; umask 022; set -C; cat > '" + remoteTmp + "'");
                    if (pipeRes.code == 0) {
                        pushedOk = true;
                    }
                }
            }
        }

        int myUserId = Math.max(0, android.os.Process.myUid() / 100000);
        String fallbackExtract = buildMultiApkUnzipFallback(myUserId, "'" + remoteTmp + "'");
        String bootstrapCmd = (pushedOk
                ? ("if [ ! -f '" + remoteTmp + "' ] || [ -L '" + remoteTmp + "' ] || [ ! -s '" + remoteTmp + "' ]; then "
                        + "rm -f '" + remoteTmp + "' 2>/dev/null; " + fallbackExtract + "; fi; ")
                : ("rm -f '" + remoteTmp + "' 2>/dev/null; " + fallbackExtract + "; "))
                + buildShellSwapAndLaunchScript("'" + remoteTmp + "'", expectedSha);

        CmdRes shRes = runAdb(appCtx, 24000, "-s", target, "shell", bootstrapCmd);
        if (shRes.code != 0 || shRes.output == null || !shRes.output.contains("LAUNCH_DONE")) {
            try { runAdb(appCtx, 2000, "-s", target, "shell", "rm -f '" + remoteTmp + "' 2>/dev/null"); } catch (Throwable ignored) {}
            postResult(listener, false, "远程 Shell 启动失败:\n" + shRes.output);
            return;
        }

        if (isAutoCloseWifiAdbEnabled(appCtx)) {
            try { runAdb(appCtx, 2500, "-s", target, "shell", "settings put global adb_wifi_enabled 0"); } catch (Throwable ignored) {}
        }

        awaitDaemonBinderReady(appCtx, false, listener, "无线调试激活成功，ExtBridge 服务已就绪！");
    }

    public static boolean isAutoCloseWifiAdbEnabled(Context ctx) {
        if (ctx == null) return false;
        SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_AUTO_CLOSE_WIFI_ADB, false);
    }

    public static void setAutoCloseWifiAdbEnabled(Context ctx, boolean enabled) {
        if (ctx == null) return;
        SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_AUTO_CLOSE_WIFI_ADB, enabled).apply();
    }

    public static boolean isAutoRootEnabled(Context ctx) {
        if (ctx == null) return false;
        SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_AUTO_ROOT, false);
    }

    public static void setAutoRootEnabled(Context ctx, boolean enabled) {
        if (ctx == null) return;
        SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_AUTO_ROOT, enabled).apply();
    }

    public static boolean hasSuBinaryHint() {
        String[] paths = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su",
                "/vendor/bin/su", "/debug_ramdisk/su",
                "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su"
        };
        for (String p : paths) {
            try {
                File f = new File(p);
                if (f.exists()) return true;
            } catch (Throwable ignored) {}
        }
        String envPath = System.getenv("PATH");
        if (envPath != null) {
            for (String dir : envPath.split(":")) {
                if (dir == null || dir.isEmpty()) continue;
                try {
                    if (new File(dir, "su").exists()) return true;
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    private static String computeAssetJarSha256(Context ctx) {
        if (ctx == null) return "";
        try (InputStream in = ctx.getAssets().open("extbridge.jar")) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            byte[] dig = md.digest();
            StringBuilder hex = new StringBuilder(64);
            for (byte b : dig) {
                hex.append(String.format(Locale.US, "%02x", b & 0xff));
            }
            return hex.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String randomHex16() {
        byte[] rnd = new byte[8];
        new SecureRandom().nextBytes(rnd);
        StringBuilder sb = new StringBuilder(16);
        for (byte b : rnd) {
            sb.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static String buildMultiApkUnzipFallback(int userId, String dstExpr) {
        return "for p in $(pm path --user " + userId + " com.extbridge.helper 2>/dev/null | sed -n 's/^package://p' | tr -d '\\r') "
                + "$(pm path com.extbridge.helper 2>/dev/null | sed -n 's/^package://p' | tr -d '\\r'); do "
                + "if [ -n \"$p\" ] && [ -f \"$p\" ] && [ ! -L \"$p\" ]; then "
                + "unzip -p \"$p\" assets/extbridge.jar > " + dstExpr + " 2>/dev/null; "
                + "[ -s " + dstExpr + " ] && break; fi; done";
    }

    public static void rootBootstrapAsync(final Context ctx, final boolean markAutoRoot, final ProgressListener listener) {
        final Context appCtx = ctx.getApplicationContext();
        sExec.execute(() -> {
            postStep(listener, "正在请求 Root (su) 权限...");
            String[] staged = stageBridgeJarWithSha256(appCtx);
            if (staged == null || staged[0] == null || staged[1] == null || staged[1].isEmpty()) {
                postResult(listener, false, "无法从 APK 释放并校验 extbridge.jar");
                return;
            }
            String qStage = staged[0].replace("'", "");
            String expSha = staged[1].toLowerCase(Locale.US);
            int myUserId = Math.max(0, android.os.Process.myUid() / 100000);
            // Literal cryptographic-nonce path avoids environment variable wiping across `su 2000`
            String rootTmp = "/data/local/tmp/extb_" + randomHex16() + ".jar";
            String shellInner = buildShellSwapAndLaunchScript("'" + rootTmp + "'", expSha);
            String qShellInner = shellInner.replace("'", "'\\''");
            // Root ONLY populates an O_CREAT|O_EXCL (`set -C`) temp jar verified against expSha
            // (with automatic multi-user/multi-split `pm path` fallback when Magisk/KernelSU Mount Namespace is Isolated),
            // chowns it 2000:2000, and transitions to uid 2000 (`u:r:shell:s0`) before running app_process!
            String script = "UID_NOW=$(id -u 2>/dev/null || echo -1); "
                    + "if [ \"$UID_NOW\" != \"0\" ]; then echo NO_ROOT:$UID_NOW; exit 12; fi; "
                    + "if ! (umask 022; set -C; : > '" + rootTmp + "') 2>/dev/null || [ -L '" + rootTmp + "' ]; then "
                    + "rm -f '" + rootTmp + "' 2>/dev/null; echo ERR_MKTEMP; exit 21; fi; "
                    + "if [ -f '" + qStage + "' ] && [ ! -L '" + qStage + "' ] && [ -s '" + qStage + "' ]; then "
                    + "cat '" + qStage + "' > '" + rootTmp + "' 2>/dev/null; fi; "
                    + "SRC_SHA=$(sha256sum '" + rootTmp + "' 2>/dev/null | cut -d' ' -f1); "
                    + "if [ \"$SRC_SHA\" != '" + expSha + "' ]; then "
                    + buildMultiApkUnzipFallback(myUserId, "'" + rootTmp + "'") + "; "
                    + "SRC_SHA=$(sha256sum '" + rootTmp + "' 2>/dev/null | cut -d' ' -f1); "
                    + "fi; "
                    + "if [ \"$SRC_SHA\" != '" + expSha + "' ] || [ \"$(dd if='" + rootTmp + "' bs=1 count=2 2>/dev/null)\" != \"PK\" ]; then "
                    + "rm -f '" + rootTmp + "' 2>/dev/null; echo ERR_STAGE_HASH:$SRC_SHA; exit 21; fi; "
                    + "chmod 644 '" + rootTmp + "' && chown -h 2000:2000 '" + rootTmp + "' "
                    + "|| { rm -f '" + rootTmp + "' 2>/dev/null; echo ERR_STAGE_CHOWN; exit 21; }; "
                    + "run_as_shell() { "
                    + "su 2000 -c \"$1\" && return 0; rc=$?; [ \"$rc\" -ge 20 ] && [ \"$rc\" -le 29 ] && return \"$rc\"; "
                    + "su shell -c \"$1\" 2>/dev/null && return 0; rc=$?; [ \"$rc\" -ge 20 ] && [ \"$rc\" -le 29 ] && return \"$rc\"; "
                    + "su 2000 /system/bin/sh -c \"$1\" 2>/dev/null && return 0; rc=$?; [ \"$rc\" -ge 20 ] && [ \"$rc\" -le 29 ] && return \"$rc\"; "
                    + "/system/bin/sh -c \"$1\"; "
                    + "}; "
                    + "run_as_shell '" + qShellInner + "'; RC=$?; "
                    + "rm -f '" + rootTmp + "' 2>/dev/null; exit $RC";

            CmdRes res = runSu(25000, script);
            String out = res.output == null ? "" : res.output.trim();
            if (res.code != 0 || !out.contains("LAUNCH_DONE")) {
                String hint = out.contains("NO_ROOT")
                        ? "未获取到 Root 权限（请在 Magisk / KernelSU / APatch 弹窗中允许授权，或改用无线调试/ADB激活）。"
                        : ("Root 执行失败（设备可能未 Root 或未对 ExtBridge 授权 Root）：\n" + out);
                postResult(listener, false, hint);
                return;
            }

            if (markAutoRoot) {
                setAutoRootEnabled(appCtx, true);
            }
            awaitDaemonBinderReady(appCtx, true, listener, "Root 一键激活成功，ExtBridge 服务已就绪！");
        });
    }

    private static String[] stageBridgeJarWithSha256(Context ctx) {
        File outFile = new File(ctx.getFilesDir(), "extbridge_stage.jar");
        try (InputStream in = ctx.getAssets().open("extbridge.jar");
             OutputStream out = new FileOutputStream(outFile, false)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
                out.write(buf, 0, n);
            }
            out.flush();
            outFile.setReadable(false, false);
            outFile.setReadable(true, true);
            outFile.setWritable(false, false);
            outFile.setWritable(true, true);
            byte[] dig = md.digest();
            StringBuilder hex = new StringBuilder(64);
            for (byte b : dig) {
                hex.append(String.format(Locale.US, "%02x", b & 0xff));
            }
            return new String[]{outFile.getAbsolutePath(), hex.toString()};
        } catch (Throwable t) {
            Log.w(TAG, "stageBridgeJar failed", t);
            return null;
        }
    }

    private static String buildShellSwapAndLaunchScript(String tmpJarExpr, String expectedSha256) {
        String cleanSha = (expectedSha256 == null) ? "" : expectedSha256.trim().toLowerCase(Locale.US);
        String shaGuard = cleanSha.matches("^[0-9a-f]{64}$")
                ? ("ACT_SHA=$(sha256sum " + tmpJarExpr + " 2>/dev/null | cut -d' ' -f1); "
                        + "if [ -n \"$ACT_SHA\" ] && [ \"$ACT_SHA\" != '" + cleanSha + "' ]; then "
                        + "rm -f " + tmpJarExpr + "; echo ERR_JAR_SHA256:$ACT_SHA; exit 21; fi; ")
                : "";
        String postSwapShaGuard = cleanSha.matches("^[0-9a-f]{64}$")
                ? ("FINAL_SHA=$(sha256sum /data/local/tmp/extbridge.jar 2>/dev/null | cut -d' ' -f1); "
                        + "if [ -n \"$FINAL_SHA\" ] && [ \"$FINAL_SHA\" != '" + cleanSha + "' ]; then "
                        + "echo ERR_FINAL_SHA256:$FINAL_SHA; exit 22; fi; ")
                : "";
        String aliveExpr = "(pgrep -f '^app_process.*ExtBridge' >/dev/null 2>&1 || pgrep -f '^/system/bin/app_process.*ExtBridge' >/dev/null 2>&1)";
        return "if [ ! -f " + tmpJarExpr + " ] || [ -L " + tmpJarExpr + " ] || [ ! -s " + tmpJarExpr + " ]; then "
                + "rm -f " + tmpJarExpr + "; echo ERR_STAGE_EMPTY; exit 21; fi; "
                + "if [ \"$(dd if=" + tmpJarExpr + " bs=1 count=2 2>/dev/null)\" != \"PK\" ]; then "
                + "rm -f " + tmpJarExpr + "; echo ERR_STAGE_NOT_ZIP; exit 21; fi; "
                + shaGuard
                + "chmod 644 " + tmpJarExpr + " || { rm -f " + tmpJarExpr + "; echo ERR_CHMOD_JAR; exit 21; }; "
                + "(CLASSPATH=" + tmpJarExpr + " /system/bin/app_process /data/local/tmp com.extbridge.daemon.ExtBridge --stop >/dev/null 2>&1 || true); "
                + "if " + aliveExpr + "; then "
                + "(pkill -f '^app_process.*ExtBridge' 2>/dev/null; pkill -f '^/system/bin/app_process.*ExtBridge' 2>/dev/null; true); "
                + "sleep 1.0; "
                + "(pkill -f -l KILL '^app_process.*ExtBridge' 2>/dev/null; pkill -f -l KILL '^/system/bin/app_process.*ExtBridge' 2>/dev/null; true); fi; "
                + "rm -f /data/local/tmp/extbridge.jar; "
                + "if ! mv -f " + tmpJarExpr + " /data/local/tmp/extbridge.jar || [ -L /data/local/tmp/extbridge.jar ] || [ ! -s /data/local/tmp/extbridge.jar ]; then "
                + "rm -f " + tmpJarExpr + "; echo ERR_SWAP_JAR; exit 22; fi; "
                + postSwapShaGuard
                + "chown -h 2000:2000 /data/local/tmp/extbridge.jar 2>/dev/null; "
                + "LOG_DST=/dev/null; "
                + "rm -f /data/local/tmp/extbridge.log 2>/dev/null; "
                + "if (umask 022; set -C; : > /data/local/tmp/extbridge.log) 2>/dev/null && [ -f /data/local/tmp/extbridge.log ] && [ ! -L /data/local/tmp/extbridge.log ]; then "
                + "chown -h 2000:2000 /data/local/tmp/extbridge.log 2>/dev/null; LOG_DST=/data/local/tmp/extbridge.log; fi; "
                + "appops set com.extbridge.helper SYSTEM_ALERT_WINDOW allow 2>/dev/null; "
                + "appops set com.extbridge.helper POST_NOTIFICATION allow 2>/dev/null; "
                + "appops set com.extbridge.helper RUN_ANY_IN_BACKGROUND allow 2>/dev/null; "
                + "appops set com.extbridge.helper 10021 allow 2>/dev/null; "
                + "appops set com.extbridge.helper ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null; "
                + "pm grant com.extbridge.helper android.permission.POST_NOTIFICATIONS 2>/dev/null; "
                + "dumpsys deviceidle whitelist +com.extbridge.helper >/dev/null 2>&1; "
                + "CUR_CTX=$(id -Z 2>/dev/null || echo ''); "
                + "case \"$CUR_CTX\" in "
                + "*:shell:*) "
                + "(CLASSPATH=/data/local/tmp/extbridge.jar setsid /system/bin/app_process /data/local/tmp com.extbridge.daemon.ExtBridge </dev/null >>\"$LOG_DST\" 2>&1 &); sleep 0.5 ;; "
                + "*) "
                + "(CLASSPATH=/data/local/tmp/extbridge.jar setsid runcon u:r:shell:s0 /system/bin/app_process /data/local/tmp com.extbridge.daemon.ExtBridge </dev/null >>\"$LOG_DST\" 2>&1 &); sleep 0.5; "
                + "if ! " + aliveExpr + "; then "
                + "(CLASSPATH=/data/local/tmp/extbridge.jar setsid /system/bin/app_process /data/local/tmp com.extbridge.daemon.ExtBridge </dev/null >>\"$LOG_DST\" 2>&1 &); sleep 0.5; fi ;; "
                + "esac; "
                + "if ! " + aliveExpr + "; then "
                + "(CLASSPATH=/data/local/tmp/extbridge.jar nohup app_process /data/local/tmp com.extbridge.daemon.ExtBridge </dev/null >>\"$LOG_DST\" 2>&1 &); sleep 0.6; fi; "
                + "if " + aliveExpr + "; then echo LAUNCH_DONE; else echo ERR_DAEMON_DIED; [ \"$LOG_DST\" != \"/dev/null\" ] && cat \"$LOG_DST\" 2>/dev/null; exit 24; fi";
    }

    public static String buildDaemonBootstrapScript(String stagedJarPath) {
        return buildDaemonBootstrapScript(stagedJarPath, "");
    }

    public static String buildDaemonBootstrapScript(String stagedJarPath, String expectedSha256) {
        int myUserId = Math.max(0, android.os.Process.myUid() / 100000);
        String tmpPath = "/data/local/tmp/extb_" + randomHex16() + ".jar";
        String extractLogic;
        if (stagedJarPath != null && !stagedJarPath.isEmpty()) {
            String qStage = stagedJarPath.replace("'", "");
            extractLogic = "if [ -f '" + qStage + "' ] && [ ! -L '" + qStage + "' ] && [ -s '" + qStage + "' ]; then cat '" + qStage + "' > '" + tmpPath + "'; "
                    + "else " + buildMultiApkUnzipFallback(myUserId, "'" + tmpPath + "'") + "; fi";
        } else {
            extractLogic = buildMultiApkUnzipFallback(myUserId, "'" + tmpPath + "'");
        }
        // Fail-closed BEFORE stopping any running daemon or touching /data/local/tmp/extbridge.jar
        return "if ! (umask 022; set -C; : > '" + tmpPath + "') 2>/dev/null || [ -L '" + tmpPath + "' ]; then "
                + "rm -f '" + tmpPath + "' 2>/dev/null; echo ERR_TMP_SYMLINK; exit 21; fi; "
                + extractLogic + "; "
                + buildShellSwapAndLaunchScript("'" + tmpPath + "'", expectedSha256);
    }

    private static void awaitDaemonBinderReady(Context appCtx, boolean fromRoot, ProgressListener listener, String successMsg) {
        postStep(listener, "守护进程已拉起，正在验证 Binder 连接...");
        long deadline = System.currentTimeMillis() + 12000;
        while (System.currentTimeMillis() < deadline) {
            BridgeClient.requestBinder(appCtx);
            if (BridgeClient.getStatus().connected) {
                cancelPairingNotification(appCtx);
                postResult(listener, true, successMsg);
                return;
            }
            try { Thread.sleep(250); } catch (InterruptedException ignored) { break; }
        }
        String tailDiag = "";
        if (fromRoot) {
            CmdRes diag = runSu(3000, "tail -n 8 /data/local/tmp/extbridge.log 2>/dev/null");
            if (diag.output != null && !diag.output.trim().isEmpty()) {
                tailDiag = "\n日志摘要:\n" + diag.output.trim();
            }
        }
        postResult(listener, false, "守护进程命令已下发，但暂未收到 Binder 握手，请稍等几秒或查看 /data/local/tmp/extbridge.log" + tailDiag);
    }

    private static CmdRes runSu(long timeoutMs, String shellCmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", shellCmd).redirectErrorStream(true).start();
            try {
                p.getOutputStream().close();
            } catch (Throwable ignored) {}
            final StringBuilder sb = new StringBuilder();
            final Process proc = p;
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (sb.length() < 8192) {
                            if (sb.length() > 0) sb.append('\n');
                            sb.append(line);
                        }
                    }
                } catch (Throwable ignored) {}
            });
            reader.start();

            boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                reader.join(500);
                return new CmdRes(-1, "Root 命令执行超时 (" + timeoutMs + "ms)\n" + sb);
            }
            reader.join(1000);
            return new CmdRes(p.exitValue(), sb.toString());
        } catch (Throwable t) {
            if (p != null) try { p.destroyForcibly(); } catch (Throwable ignored) {}
            return new CmdRes(-1, "未找到 su 或无 Root 权限: " + t.getMessage());
        }
    }

    private static final class CmdRes {
        final int code;
        final String output;
        CmdRes(int code, String output) {
            this.code = code;
            this.output = output;
        }
    }

    private static CmdRes runAdb(Context ctx, long timeoutMs, String... args) {
        return runAdbWithStdinFile(ctx, timeoutMs, null, args);
    }

    private static CmdRes runAdbWithStdinFile(Context ctx, long timeoutMs, File stdinFile, String... args) {
        Process p = null;
        try {
            File adbBin = getAdbFile(ctx);
            File homeDir = new File(ctx.getFilesDir(), "adb_home");
            File dotAndroid = new File(homeDir, ".android");
            File tmpDir = new File(ctx.getCacheDir(), "adb_tmp");
            if (!dotAndroid.exists()) dotAndroid.mkdirs();
            if (!tmpDir.exists()) tmpDir.mkdirs();

            String[] cmd = new String[args.length + 1];
            cmd[0] = adbBin.getAbsolutePath();
            System.arraycopy(args, 0, cmd, 1, args.length);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(homeDir);
            pb.redirectErrorStream(true);
            File sockFile = getPrivateAdbSocketFile(ctx);
            Map<String, String> env = new HashMap<>(pb.environment());
            env.put("HOME", homeDir.getAbsolutePath());
            env.put("ANDROID_USER_HOME", dotAndroid.getAbsolutePath());
            env.put("ANDROID_SDK_HOME", homeDir.getAbsolutePath());
            env.put("TMPDIR", tmpDir.getAbsolutePath());
            env.remove("ANDROID_ADB_SERVER_PORT");
            env.put("ADB_SERVER_SOCKET", "localfilesystem:" + sockFile.getAbsolutePath());
            env.put("ADB_MDNS", "0");
            env.put("ADB_MDNS_OPENSCREEN", "0");
            pb.environment().clear();
            pb.environment().putAll(env);

            p = pb.start();
            final StringBuilder sb = new StringBuilder();
            final Process proc = p;
            if (stdinFile != null && stdinFile.isFile()) {
                Thread writer = new Thread(() -> {
                    try (InputStream fis = new FileInputStream(stdinFile);
                         OutputStream pos = proc.getOutputStream()) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = fis.read(buf)) > 0) {
                            pos.write(buf, 0, n);
                        }
                        pos.flush();
                    } catch (Throwable ignored) {}
                }, "ExtAdbStdin");
                writer.start();
            } else {
                try {
                    p.getOutputStream().close();
                } catch (Throwable ignored) {}
            }
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (sb.length() < 8192) {
                            if (sb.length() > 0) sb.append('\n');
                            sb.append(line);
                        }
                    }
                } catch (Throwable ignored) {}
            });
            reader.start();

            boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                reader.join(500);
                return new CmdRes(-1, "命令执行超时 (" + timeoutMs + "ms)\n" + sb);
            }
            reader.join(1000);
            return new CmdRes(p.exitValue(), sb.toString());
        } catch (Throwable t) {
            if (p != null) try { p.destroyForcibly(); } catch (Throwable ignored) {}
            return new CmdRes(-1, "执行出错: " + t.getMessage());
        }
    }

    public static void showPairingNotification(Context ctx, String statusText) {
        sPairNotifShowing = true;
        if (sNsd == null && ctx != null) {
            startDiscovery(ctx.getApplicationContext(), sDiscListener);
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    PAIR_CHANNEL_ID, "无线调试快速配对", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("在系统配对码弹窗上方直接输入6位配对码完成激活");
            nm.createNotificationChannel(ch);
        }

        RemoteInput remoteInput = new RemoteInput.Builder(KEY_PAIR_INPUT)
                .setLabel("输入6位配对码（或 配对码:端口）")
                .build();
        Intent replyIntent = new Intent(ctx, AdbPairingReceiver.class);
        replyIntent.setAction(ACTION_PAIR_REPLY);
        int mutFlag = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent replyPi = PendingIntent.getBroadcast(
                ctx, 2001, replyIntent, PendingIntent.FLAG_UPDATE_CURRENT | mutFlag);
        Notification.Action replyAction = new Notification.Action.Builder(
                android.R.drawable.ic_menu_send, "输入配对码", replyPi)
                .addRemoteInput(remoteInput)
                .build();

        Intent openIntent = new Intent(ctx, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(
                ctx, 2002, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        DiscoveryState ds = getDiscoveryState();
        String body = statusText;
        if (body == null || body.isEmpty()) {
            body = ds.pairingPort > 0
                    ? ("已发现配对端口 " + ds.pairingPort + "，下拉后点「输入配对码」填6位数字即可")
                    : "打开系统「使用配对码配对设备」后，点下方「输入配对码」直接输入（格式: 配对码 或 配对码:端口）";
        }

        Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, PAIR_CHANNEL_ID)
                : new Notification.Builder(ctx);
        nb.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("ExtBridge 无线调试配对助手")
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(openPi)
                .addAction(replyAction)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false);

        nm.notify(PAIR_NOTIF_ID, nb.build());
    }

    public static void cancelPairingNotification(Context ctx) {
        sPairNotifShowing = false;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(PAIR_NOTIF_ID);
    }

    private static void postStep(final ProgressListener l, final String msg) {
        if (l != null) sMain.post(() -> l.onStep(msg));
    }

    private static void postResult(final ProgressListener l, final boolean ok, final String msg) {
        if (l != null) sMain.post(() -> l.onResult(ok, msg));
    }

    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return def; }
    }
}
