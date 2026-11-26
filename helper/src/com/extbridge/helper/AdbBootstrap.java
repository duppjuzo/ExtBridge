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
import android.os.SystemClock;
import android.util.Log;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** On-device activation. Every entry uses the same owned, cancellable staged flow. */
public final class AdbBootstrap {
    private static final String TAG = "ExtBridgeAdb";
    public static final String ACTION_PAIR_REPLY = "com.extbridge.helper.ACTION_PAIR_REPLY";
    public static final String ACTION_STOP_PAIR_NOTIF = "com.extbridge.helper.ACTION_STOP_PAIR_NOTIF";
    public static final String KEY_PAIR_INPUT = "pair_input";
    private static final String PAIR_CHANNEL_ID = "extbridge_adb_pair";
    private static final int PAIR_NOTIF_ID = 3901;
    private static final String PREFS_NAME = "extbridge_prefs";
    private static final String KEY_AUTO_ROOT = "auto_root_enabled";
    private static final String SERVICE_PAIRING = "_adb-tls-pairing._tcp.";
    private static final String SERVICE_CONNECT = "_adb-tls-connect._tcp.";
    private static final ExecutorService sExec = Executors.newSingleThreadExecutor();
    private static final Handler sMain = new Handler(Looper.getMainLooper());
    private static final ActivationSession SESSION = new ActivationSession();
    private static final ThreadLocal<ActivationSession.Ticket> WORKER = new ThreadLocal<>();
    private static volatile Context sApp;
    private static volatile String lastOutcome = "NONE", lastStage = "NONE", lastMode = "NONE", lastToken = "";
    private static volatile int rootExecutions, adbExecutions;
    public static void diagnostics(android.os.Bundle data) {
        data.putString("last_outcome", lastOutcome); data.putString("last_stage", lastStage);
        data.putString("last_mode", lastMode); data.putString("last_request_token", lastToken);
        data.putInt("root_command_count", rootExecutions); data.putInt("adb_command_count", adbExecutions);
    }
    public static android.os.Bundle nativeAdbDiagnostics(Context context) {
        android.os.Bundle data = new android.os.Bundle();
        ActivationFlow.Command result = runAdb(context, 5000, "version");
        data.putInt("exit", result.code); data.putString("output", result.output);
        data.putBoolean("executable", isNativeAdbAvailable(context)); return data;
    }
    private static volatile long discoveryGeneration;
    private static NsdManager sNsd;
    private static NsdManager.DiscoveryListener sPairDisc, sConnDisc;
    private static WifiManager.MulticastLock sMcastLock;
    private static DiscoveryListener sDiscListener;
    private static final DiscoveryState sDiscState = new DiscoveryState();
    private static final ArrayDeque<ResolveRequest> sResolveQueue = new ArrayDeque<>();
    private static final Map<String, NsdServiceInfo> sLiveServices = new HashMap<>();
    private static boolean sResolving;
    private static String pairingName = "", connectName = "";
    private static long pairingSeen, connectSeen;

    private AdbBootstrap() {}
    public interface DiscoveryListener { void onDiscoveryUpdated(DiscoveryState state); }
    public interface ProgressListener {
        void onStep(String message);
        void onResult(boolean success, String message);
        default void onActivationResult(ActivationFlow.Result result) { onResult(result.activated(), result.message); }
    }
    public static final class DiscoveryState {
        public String pairingHost = "", connectHost = "";
        public int pairingPort, connectPort;
    }
    private static final class ResolveRequest {
        final NsdServiceInfo info; final long generation;
        ResolveRequest(NsdServiceInfo info, long generation) { this.info = info; this.generation = generation; }
    }
    public static void initialize(Context context) { if (context != null) sApp = context.getApplicationContext(); }
    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE); }
    public static boolean isAutoRootEnabled(Context context) { return context != null && prefs(context).getBoolean(KEY_AUTO_ROOT, false); }
    public static boolean setAutoRootEnabled(Context context, boolean enabled) {
        initialize(context);
        boolean previous = isAutoRootEnabled(context);
        boolean saved = prefs(context).edit().putBoolean(KEY_AUTO_ROOT, enabled).commit();
        if (!saved) prefs(context).edit().putBoolean(KEY_AUTO_ROOT, previous).commit();
        if (!enabled) SESSION.cancelAutomatic();
        return saved;
    }
    public static boolean wasCancelled(String token) {
        Context context = sApp;
        return context != null && token != null && !token.isEmpty()
                && prefs(context).getStringSet("cancelled_activations", Collections.emptySet()).contains(token);
    }
    public static void cancelActivation(ActivationSession.Ticket ticket) { if (ticket != null) ticket.cancel(); }
    public static void cancelAllActivations() { SESSION.cancelAll(); }
    public static void cancelNotificationActivation() { SESSION.cancelNotification(); }
    private static void invalidateLaunch(Context app, ActivationSession.Ticket ticket, File cancelFile) {
        synchronized (AdbBootstrap.class) {
            Set<String> cancelled = new HashSet<>(prefs(app).getStringSet("cancelled_activations", Collections.emptySet()));
            cancelled.add(ticket.token); prefs(app).edit().putStringSet("cancelled_activations", cancelled).commit();
        }
        try { new FileOutputStream(cancelFile).close(); } catch (Throwable error) { Log.w(TAG, "Unable to write cancellation marker", error); }
    }

    public static synchronized DiscoveryState getDiscoveryState() {
        DiscoveryState copy = new DiscoveryState();
        // A live NSD registration stays valid until loss/stop/network change, not an arbitrary one-minute timeout.
        if (sNsd != null && pairingSeen > 0 && localHost(sDiscState.pairingHost)) { copy.pairingHost = sDiscState.pairingHost; copy.pairingPort = sDiscState.pairingPort; }
        if (sNsd != null && connectSeen > 0 && localHost(sDiscState.connectHost)) { copy.connectHost = sDiscState.connectHost; copy.connectPort = sDiscState.connectPort; }
        return copy;
    }
    public static synchronized void startDiscovery(Context context, DiscoveryListener listener) {
        initialize(context); if (listener != null || sNsd == null) sDiscListener = listener;
        if (sNsd != null) { notifyDiscListener(); return; }
        long generation = ++discoveryGeneration;
        clearDiscovery();
        try {
            WifiManager wifi = (WifiManager) sApp.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) { sMcastLock = wifi.createMulticastLock("extbridge_mdns"); sMcastLock.setReferenceCounted(false); sMcastLock.acquire(); }
            sNsd = (NsdManager) sApp.getSystemService(Context.NSD_SERVICE);
            if (sNsd == null) return;
            sPairDisc = discovery(true, generation); sConnDisc = discovery(false, generation);
            sNsd.discoverServices(SERVICE_PAIRING, NsdManager.PROTOCOL_DNS_SD, sPairDisc);
            sNsd.discoverServices(SERVICE_CONNECT, NsdManager.PROTOCOL_DNS_SD, sConnDisc);
        } catch (Throwable error) { Log.w(TAG, "Discovery unavailable", error); stopDiscovery(); }
    }
    private static void clearDiscovery() {
        sDiscState.pairingHost = ""; sDiscState.connectHost = "";
        sDiscState.pairingPort = 0; sDiscState.connectPort = 0;
        pairingName = ""; connectName = ""; pairingSeen = 0; connectSeen = 0;
        sLiveServices.clear();
    }
    public static synchronized void stopDiscovery() {
        ++discoveryGeneration; sDiscListener = null;
        if (sNsd != null) {
            try { if (sPairDisc != null) sNsd.stopServiceDiscovery(sPairDisc); } catch (Throwable ignored) {}
            try { if (sConnDisc != null) sNsd.stopServiceDiscovery(sConnDisc); } catch (Throwable ignored) {}
        }
        sPairDisc = null; sConnDisc = null; sNsd = null; sResolveQueue.clear(); sResolving = false; clearDiscovery();
        if (sMcastLock != null) { try { if (sMcastLock.isHeld()) sMcastLock.release(); } catch (Throwable ignored) {} sMcastLock = null; }
    }
    private static NsdManager.DiscoveryListener discovery(boolean pairing, long generation) {
        return new NsdManager.DiscoveryListener() {
            public void onDiscoveryStarted(String type) {}
            public void onStartDiscoveryFailed(String type, int code) {}
            public void onStopDiscoveryFailed(String type, int code) {}
            public void onDiscoveryStopped(String type) {}
            public void onServiceFound(NsdServiceInfo info) { synchronized (AdbBootstrap.class) {
                if (generation != discoveryGeneration) return;
                sLiveServices.put(serviceKey(info), info);
                sResolveQueue.addLast(new ResolveRequest(info, generation)); resolveNext();
            } }
            public void onServiceLost(NsdServiceInfo info) { synchronized (AdbBootstrap.class) {
                if (generation != discoveryGeneration) return;
                sLiveServices.remove(serviceKey(info));
                if (pairing && info.getServiceName().equals(pairingName)) { sDiscState.pairingPort = 0; pairingSeen = 0; }
                if (!pairing && info.getServiceName().equals(connectName)) { sDiscState.connectPort = 0; connectSeen = 0; }
                notifyDiscListener();
            } }
        };
    }
    @SuppressWarnings("deprecation")
    private static void resolveNext() {
        if (sResolving || sResolveQueue.isEmpty() || sNsd == null) return;
        ResolveRequest request = sResolveQueue.removeFirst(); sResolving = true;
        try { sNsd.resolveService(request.info, new NsdManager.ResolveListener() {
            public void onResolveFailed(NsdServiceInfo info, int code) { synchronized (AdbBootstrap.class) {
                if (request.generation != discoveryGeneration) return; sResolving = false; resolveNext();
            } }
            public void onServiceResolved(NsdServiceInfo info) { synchronized (AdbBootstrap.class) {
                if (request.generation != discoveryGeneration) return;
                if (sLiveServices.get(serviceKey(request.info)) != request.info) {
                    sResolving = false; resolveNext(); return;
                }
                String host = info.getHost() == null ? "" : info.getHost().getHostAddress();
                if (host != null && host.contains("%")) host = host.substring(0, host.indexOf('%'));
                if (localHost(host) && ActivationFlow.validPort(info.getPort())) {
                    if (info.getServiceType().contains("pairing")) {
                        sDiscState.pairingHost = host; sDiscState.pairingPort = info.getPort(); pairingName = info.getServiceName(); pairingSeen = SystemClock.elapsedRealtime();
                    } else {
                        sDiscState.connectHost = host; sDiscState.connectPort = info.getPort(); connectName = info.getServiceName(); connectSeen = SystemClock.elapsedRealtime();
                    }
                    notifyDiscListener();
                }
                sResolving = false; resolveNext();
            } }
        }); } catch (Throwable error) { sResolving = false; resolveNext(); }
    }
    private static String serviceKey(NsdServiceInfo info) { return info.getServiceType() + "/" + info.getServiceName(); }
    private static void notifyDiscListener() {
        DiscoveryListener listener = sDiscListener; long generation = discoveryGeneration;
        if (listener == null) return;
        DiscoveryState state = getDiscoveryState();
        sMain.post(() -> { synchronized (AdbBootstrap.class) {
            if (generation == discoveryGeneration && listener == sDiscListener) listener.onDiscoveryUpdated(state);
        } });
    }
    private static boolean localHost(String host) {
        if (!ActivationFlow.validHost(host)) return false;
        try {
            InetAddress address = InetAddress.getByName(host);
            if (address.isLoopbackAddress()) return true;
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                Enumeration<InetAddress> addresses = interfaces.nextElement().getInetAddresses();
                while (addresses.hasMoreElements()) if (address.equals(addresses.nextElement())) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }
    public static String getLocalIpv4() {
        String fallback = "127.0.0.1";
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                String name = network.getName().toLowerCase(Locale.ROOT);
                if (name.startsWith("tun") || name.startsWith("ppp")) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        if (name.startsWith("wlan") || name.startsWith("eth")) return address.getHostAddress();
                        fallback = address.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return fallback;
    }
    public static String buildPcAdbCommand() { return DaemonLaunchScript.pcCommand(); }
    private static File getPrivateAdbSocketFile(Context context) {
        File sockDir = new File(context.getCacheDir(), "asrv");
        if (!sockDir.exists()) sockDir.mkdirs();
        // 0700: only the app process can traverse this directory, so other
        // networking apps on the device cannot reach the local adb server.
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
        // Always drop the socket file: a server left behind would keep the app-private
        // 0700 unix socket alive for the next activation to collide with.
        try {
            File sockFile = getPrivateAdbSocketFile(appCtx);
            if (sockFile.exists()) sockFile.delete();
        } catch (Throwable ignored) {}
    }

    private static File getAdbFile(Context context) { return new File(context.getApplicationInfo().nativeLibraryDir, "libadb.so"); }
    public static boolean isNativeAdbAvailable(Context context) { File file = getAdbFile(context); return file.isFile() && file.canExecute(); }
    public static boolean hasSuBinaryHint() {
        for (String path : new String[]{"/system/bin/su", "/system/xbin/su", "/sbin/su", "/vendor/bin/su", "/debug_ramdisk/su",
                "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su", "/data/adb/modules/apatch/bin/su"}) if (new File(path).exists()) return true;
        return false;
    }
    public static ActivationSession.Ticket rootBootstrapAsync(Context context, boolean ignoredMarkAutoRoot, ProgressListener listener) {
        return submit(context, new ActivationFlow.Request(ActivationSession.Mode.ROOT, "", 0, "", 0), listener);
    }
    public static ActivationSession.Ticket autoRootBootstrapAsync(Context context, ProgressListener listener) {
        return submit(context, new ActivationFlow.Request(ActivationSession.Mode.AUTO_ROOT, "", 0, "", 0), listener);
    }
    public static ActivationSession.Ticket pairAndBootstrapAsync(Context context, String host, int pairPort, String code, String connectHost, int connectPort, ProgressListener listener) {
        return submit(context, new ActivationFlow.Request(ActivationSession.Mode.PAIR, host == null || host.trim().isEmpty() ? getLocalIpv4() : host, pairPort, code, connectPort), listener);
    }
    public static ActivationSession.Ticket connectAndBootstrapAsync(Context context, String host, int port, ProgressListener listener) {
        return submit(context, new ActivationFlow.Request(ActivationSession.Mode.CONNECT, host == null || host.trim().isEmpty() ? getLocalIpv4() : host, 0, "", port), listener);
    }
    public static ActivationSession.Ticket pairFromNotificationAsync(Context context, String raw, ProgressListener listener) {
        DiscoveryState state = getDiscoveryState();
        ActivationFlow.Request request = ActivationFlow.notification(raw,
                state.pairingHost.isEmpty() ? getLocalIpv4() : state.pairingHost, state.pairingPort, state.connectPort);
        return submit(context, request, listener);
    }
    private static ActivationSession.Ticket submit(Context context, ActivationFlow.Request request, ProgressListener listener) {
        initialize(context);
        ActivationSession.Ticket ticket = SESSION.begin(request.mode);
        if (ticket == null) { if (listener != null) sMain.post(() -> listener.onActivationResult(ActivationFlow.busy())); return null; }
        Context app = context.getApplicationContext();
        File cancelFile = new File(app.getFilesDir(), "activation-cancel-" + ticket.token);
        ticket.onCancel(() -> invalidateLaunch(app, ticket, cancelFile));
        sExec.execute(() -> {
            try {
                WORKER.set(ticket);
                lastOutcome = "RUNNING"; lastStage = "VALIDATE"; lastMode = request.mode.name(); lastToken = ticket.token;
                ActivationFlow.Result result = ActivationFlow.run(request, ticket.token, new ActivationFlow.Environment() {
                    public boolean cancelled() { return ticket.isCancelled() || BridgeService.sUserStopping || request.mode == ActivationSession.Mode.AUTO_ROOT && !isAutoRootEnabled(app); }
                    public boolean adbAvailable() { return isNativeAdbAvailable(app); }
                    public boolean hostAllowed(String host) { return localHost(host); }
                    public ActivationFlow.Command adb(String... args) { return runAdb(app, 18000, args); }
                    public ActivationFlow.Command root(String script) { return runSu(18000, script); }
                    public String script(String token, String stagedJar, boolean root) { return DaemonLaunchScript.build(token, stagedJar, root ? cancelFile.getAbsolutePath() : null, root); }
                    public String stagedJar() { return stageBridgeJar(app, ticket.token); }
                    public int discoverConnectPort() { return getDiscoveryState().connectPort; }
                    public ActivationFlow.Probe probe() {
                        BridgeClient.requestBinder(app); BridgeClient.Status status = BridgeClient.getStatus();
                        return new ActivationFlow.Probe(status.connected, status.handshakeVerified, status.protocolCompatible,
                                status.daemonPid, status.daemonUid, status.activationToken, status.connectionIssue);
                    }
                    public long now() { return SystemClock.elapsedRealtime(); }
                    public void pause(long time) { try { Thread.sleep(time); } catch (InterruptedException error) { ticket.cancel(); Thread.currentThread().interrupt(); } }
                    public void step(ActivationFlow.Stage stage, String message) { if (ticket.mayDeliver()) lastStage = stage.name(); if (listener != null) sMain.post(() -> { if (ticket.mayDeliver()) listener.onStep(message); }); }
                });
                if (result.outcome == ActivationFlow.Outcome.CANCELLED) ticket.cancel();
                boolean rejectedLaunch = result.outcome == ActivationFlow.Outcome.FAILED
                        && (result.stage == ActivationFlow.Stage.DEPLOY || result.stage == ActivationFlow.Stage.VERIFY);
                if (rejectedLaunch) invalidateLaunch(app, ticket, cancelFile);
                // A failed/abandoned launch may arrive after the local adb/su process
                // returned. Revoke only this token, never an unrelated existing daemon.
                if (ticket.isCancelled() || rejectedLaunch) BridgeClient.shutdownOwned(ticket.token);
                if (ticket.sameGeneration()) { lastOutcome = result.outcome.name(); lastStage = result.stage.name(); }
                ticket.finish(); WORKER.remove();
                if (!ticket.isCancelled() && !rejectedLaunch) { cancelFile.delete(); new File(app.getFilesDir(), "extbridge-stage-" + ticket.token + ".jar").delete(); }
                if (listener != null) sMain.post(() -> { if (ticket.mayDeliver()) listener.onActivationResult(result); });
            } finally {
                // Always tear down the local adb server once activation ends so no
                // lingering server (and its private socket) survives the flow.
                stopLocalAdbServerSync(app);
            }
        });
        return ticket;
    }
    private static String stageBridgeJar(Context context, String token) {
        File file = new File(context.getFilesDir(), "extbridge-stage-" + token + ".jar");
        try (InputStream in = context.getAssets().open("extbridge.jar"); OutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) > 0) out.write(buffer, 0, count);
            return file.getAbsolutePath();
        } catch (IOException error) { throw new IllegalStateException("Unable to stage daemon", error); }
    }
    private static ActivationFlow.Command runSu(long timeout, String script) {
        return command(new ProcessBuilder("su", "-c", script), timeout);
    }
    private static ActivationFlow.Command runAdb(Context context, long timeout, String... args) {
        File home = new File(context.getFilesDir(), "adb_home"), androidHome = new File(home, ".android"), tmp = new File(context.getCacheDir(), "adb_tmp");
        androidHome.mkdirs(); tmp.mkdirs();
        String[] command = new String[args.length + 1]; command[0] = getAdbFile(context).getAbsolutePath(); System.arraycopy(args, 0, command, 1, args.length);
        ProcessBuilder builder = new ProcessBuilder(command).directory(home);
        builder.environment().put("HOME", home.getAbsolutePath()); builder.environment().put("ANDROID_USER_HOME", androidHome.getAbsolutePath());
        builder.environment().put("TMPDIR", tmp.getAbsolutePath());
        // No TCP server port: keep the local adb server on the app-private 0700
        // unix socket so other networking apps cannot connect and escalate.
        builder.environment().remove("ANDROID_ADB_SERVER_PORT");
        builder.environment().put("ADB_SERVER_SOCKET", "localfilesystem:" + getPrivateAdbSocketFile(context).getAbsolutePath());
        builder.environment().put("ADB_MDNS", "0"); builder.environment().put("ADB_MDNS_OPENSCREEN", "0");
        return command(builder, timeout);
    }
    private static ActivationFlow.Command command(ProcessBuilder builder, long timeout) {
        ActivationSession.Ticket ticket = WORKER.get();
        if (ticket != null && (ticket.isCancelled() || ticket.mode == ActivationSession.Mode.AUTO_ROOT && !isAutoRootEnabled(sApp))) return new ActivationFlow.Command(125, "CANCELLED");
        Process process = null;
        try {
            if (builder.command().get(0).equals("su")) ++rootExecutions; else ++adbExecutions;
            process = builder.redirectErrorStream(true).start();
            final Process running = process;
            if (ticket != null) ticket.attachProcess(() -> running.destroy());
            process.getOutputStream().close();
            StringBuilder output = new StringBuilder();
            Thread reader = new Thread(() -> { try (BufferedReader input = new BufferedReader(new InputStreamReader(running.getInputStream()))) {
                String line; while ((line = input.readLine()) != null) synchronized (output) { if (output.length() < 8192) output.append(line).append('\n'); }
            } catch (IOException ignored) {} });
            reader.setDaemon(true); reader.start();
            long deadline = SystemClock.elapsedRealtime() + timeout;
            boolean exited = false;
            while (!exited && SystemClock.elapsedRealtime() < deadline) {
                if (ticket != null && (ticket.isCancelled() || ticket.mode == ActivationSession.Mode.AUTO_ROOT && !isAutoRootEnabled(sApp))) {
                    ticket.cancel(); try { process.destroy(); } catch (Throwable ignored) {}
                    reader.join(200); return new ActivationFlow.Command(125, "CANCELLED");
                }
                exited = process.waitFor(100, TimeUnit.MILLISECONDS);
            }
            if (!exited) process.destroyForcibly();
            reader.join(1000);
            synchronized (output) {
                int code = exited ? process.exitValue() : -3;
                if (code != 0) Log.w(TAG, "Activation command failed: " + builder.command().get(0) + " code=" + code
                        + " output=" + output.toString().replaceAll("[0-9]{6}", "<redacted>"));
                return new ActivationFlow.Command(code, output.toString());
            }
        } catch (Throwable error) {
            if (process != null) try { process.destroy(); } catch (Throwable ignored) {}
            Log.w(TAG, "Activation executable failed", error);
            return new ActivationFlow.Command(-2, error.getClass().getSimpleName());
        }
        finally { if (ticket != null) ticket.detachProcess(); }
    }
    public static void showPairingNotification(Context context, String text) {
        initialize(context);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel(PAIR_CHANNEL_ID, "无线调试快速配对", NotificationManager.IMPORTANCE_HIGH));
        RemoteInput input = new RemoteInput.Builder(KEY_PAIR_INPUT).setLabel("输入六位配对码或 配对码:端口").build();
        Intent reply = new Intent(context, AdbPairingReceiver.class).setAction(ACTION_PAIR_REPLY);
        PendingIntent replyIntent = PendingIntent.getBroadcast(context, 2001, reply, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
        Intent open = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(context, 2002, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent cancel = new Intent(context, AdbPairingReceiver.class).setAction(ACTION_STOP_PAIR_NOTIF);
        PendingIntent cancelIntent = PendingIntent.getBroadcast(context, 2003, cancel, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(context, PAIR_CHANNEL_ID) : new Notification.Builder(context);
        String body = text == null || text.isEmpty() ? "打开系统配对弹窗后输入配对码；配对完成后仍需连接并启动服务" : text;
        builder.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("无线调试配对助手")
                .setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body)).setContentIntent(openIntent)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_send, "输入配对码", replyIntent).addRemoteInput(input).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "取消", cancelIntent).build())
                .setOnlyAlertOnce(true).setAutoCancel(false);
        manager.notify(PAIR_NOTIF_ID, builder.build());
    }
    public static void cancelPairingNotification(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(PAIR_NOTIF_ID);
    }
}
