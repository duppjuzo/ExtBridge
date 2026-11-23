package com.extbridge.helper;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.IBinder;
import android.os.Parcel;
// BEGIN VERIFIED_DAEMON_STATUS
import android.util.Log;
import java.security.SecureRandom;
// END VERIFIED_DAEMON_STATUS

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BridgeClient {
    // BEGIN VERIFIED_DAEMON_STATUS
    private static final SecureRandom PROBE_RANDOM = new SecureRandom();
    // END VERIFIED_DAEMON_STATUS
    public static final int SYS_WIFI = 1;
    public static final int SYS_BT = 2;
    public static final int SYS_AIRPLANE = 3;
    public static final int SYS_POWER_SAVE = 4;
    public static final int SYS_OPEN_SETTINGS = 5;
    public static final int SYS_SCREEN_DIM = 6;

    public static class TaskEntry {
        public int taskId;
        public String pkg;
        public int flags;
        public boolean onExt() { return (flags & 1) != 0; }
        public boolean isRunning() { return (flags & 2) != 0; }
        public boolean isTopExt() { return (flags & 4) != 0; }
        public boolean isFreeform() { return (flags & 8) != 0; }
        public boolean isInSplit() { return (flags & 16) != 0; }
    }

    public static class SysStatus {
        public boolean valid;
        public boolean wifiOn;
        public String wifiSsid = "";
        public boolean btOn;
        public String btName = "";
        public boolean airplaneOn;
        public boolean powerSaveOn;
        public boolean screenDimmed;
    }

    private static volatile Bitmap sCachedWpBitmap;
    public static class Status {
        public boolean connected;
        // BEGIN VERIFIED_DAEMON_STATUS
        public int daemonPid = -1;
        public int daemonUid = -1;
        public boolean handshakeVerified;
        public boolean protocolCompatible;
        public String activationToken = "";
        public String connectionIssue = "";
        // END VERIFIED_DAEMON_STATUS
        public int extId = -1;
        public int vdId = -1;
        public int physW = 0;
        public int physH = 0;
        public float physFps = 60f;
        public int virtW = 2560;
        public int virtH = 1440;
        public int virtDpi = 240;
        public float virtFps = 60f;
        public int inputToExt = 1;
        public int cursorX = 1280;
        public int cursorY = 720;
        public int extInputCnt = 0;
        public String modesRaw = "";
        public String renderMode = "NONE";
        public int mirrorMode = 0;
        public int fullscreenPolicy = 1;
        public int virtRot = 0;
        public int injectStatus = 1;
        public int splitActive = 0;
        public int splitRatioPct = 50;
        public String splitSlot0Pkg = "";
        public String splitSlot1Pkg = "";
    }

    public static String getOemInjectHint() {
        String brand = ((android.os.Build.MANUFACTURER != null ? android.os.Build.MANUFACTURER : "")
                + " " + (android.os.Build.BRAND != null ? android.os.Build.BRAND : "")).toLowerCase(java.util.Locale.US);
        if (brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")) {
            return "请在「开发者选项」开启「USB 调试（安全设置）」";
        }
        if (brand.contains("oppo") || brand.contains("oneplus") || brand.contains("realme")) {
            return "请在「开发者选项」开启「禁止权限监控」";
        }
        if (brand.contains("vivo") || brand.contains("iqoo")) {
            return "请在「开发者选项」开启「USB 模拟点击 / 允许模拟输入」";
        }
        return "请在「开发者选项」开启「允许模拟点击 / USB调试安全设置 / 禁止权限监控」";
    }

    private static volatile IBinder sBinder;
    private static volatile IBinder sLinkedBinder;
    private static final java.util.concurrent.CopyOnWriteArraySet<Runnable> sBinderListeners =
            new java.util.concurrent.CopyOnWriteArraySet<>();

    public static void setOnBinderReady(Runnable r) {
        if (r != null) sBinderListeners.add(r);
    }

    public static void removeOnBinderReady(Runnable r) {
        if (r != null) sBinderListeners.remove(r);
    }

    private static synchronized void linkBinderDeath(final IBinder b) {
        if (b == null || b == sLinkedBinder) return;
        sLinkedBinder = b;
        try {
            b.linkToDeath(() -> {
                if (sBinder == b) sBinder = null;
                if (sLinkedBinder == b) sLinkedBinder = null;
            }, 0);
        } catch (Throwable ignored) {}
    }

    public static void setBinder(IBinder b) {
        if (b != null && b.pingBinder()) {
            sBinder = b;
            linkBinderDeath(b);
            for (Runnable r : sBinderListeners) {
                try { r.run(); } catch (Throwable ignored) {}
            }
        }
    }

    public static void requestBinder(android.content.Context ctx) {
        if (ctx == null) return;
        try {
            android.content.Intent i1 = new android.content.Intent("com.extbridge.daemon.NEED_BINDER");
            i1.setPackage("android");
            ctx.sendBroadcast(i1);
            android.content.Intent i2 = new android.content.Intent("com.extbridge.daemon.NEED_BINDER");
            i2.setPackage("com.android.shell");
            ctx.sendBroadcast(i2);
        } catch (Throwable ignored) {}
    }

    public static IBinder getService() {
        IBinder b = sBinder;
        if (b != null) {
            if (b.pingBinder()) return b;
            sBinder = null;
        }
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            b = (IBinder) sm.getMethod("getService", String.class).invoke(null, "extbridge");
            if (b != null && b.pingBinder()) {
                sBinder = b;
                linkBinderDeath(b);
                return b;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static Status getStatus() {
        Status st = new Status();
        IBinder b = getService();
        if (b == null) return st;
        Proof identity = verifyDaemon(b);
        if (identity == null) {
            st.connectionIssue = "服务状态无法确认，请重新激活";
            return st;
        }
        st.handshakeVerified = true;
        st.daemonPid = identity.pid;
        st.daemonUid = identity.version == DaemonProtocol.PROBE_VERSION ? identity.uid : -1;
        st.protocolCompatible = identity.version == DaemonProtocol.PROBE_VERSION;
        st.activationToken = identity.token;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            if (b.transact(15, data, reply, 0)) {
                Status parsed = DaemonProtocol.readStatus(new DaemonProtocol.Reader() {
                    @Override public int remaining() { return reply.dataAvail(); }
                    @Override public int readInt() { return reply.readInt(); }
                    @Override public String readString() { return reply.readString(); }
                });
                if (!b.isBinderAlive()) {
                    st.connectionIssue = "后台服务已退出，请重新激活";
                    return st;
                }
                parsed.daemonPid = identity.pid;
                parsed.daemonUid = identity.version == DaemonProtocol.PROBE_VERSION ? identity.uid : -1;
                parsed.handshakeVerified = true;
                parsed.protocolCompatible = identity.version == DaemonProtocol.PROBE_VERSION;
                parsed.activationToken = identity.token;
                if (AdbBootstrap.wasCancelled(identity.token)) {
                    parsed.connectionIssue = "已取消的激活正在结束";
                    shutdownOwned(identity.token);
                    return parsed;
                }
                parsed.connected = true;
                return parsed;
            }
            st.connectionIssue = "后台服务未响应，请重新激活";
        } catch (Throwable error) {
            // Never return a partially parsed status marked as connected.
            Log.w("ExtBridgeConnection", "Invalid daemon status response", error);
            st.connectionIssue = "服务应答异常，请重新激活";
        } finally {
            data.recycle();
            reply.recycle();
        }
        return st;
    }

    // BEGIN VERIFIED_DAEMON_STATUS
    private static final class Proof {
        final int pid, uid, version; final String token;
        Proof(int pid, int uid, int version, String token) { this.pid = pid; this.uid = uid; this.version = version; this.token = token; }
    }
    private static Proof verifyDaemon(IBinder binder) {
        if (binder instanceof android.os.Binder) {
            Log.w("ExtBridgeConnection", "Local app Binder cannot be the shell daemon");
            return null;
        }
        Parcel request = Parcel.obtain();
        Parcel response = Parcel.obtain();
        long nonce = PROBE_RANDOM.nextLong();
        try {
            request.writeInterfaceToken(DaemonProtocol.DESCRIPTOR);
            request.writeLong(nonce);
            if (!binder.transact(DaemonProtocol.PROBE_TRANSACTION, request, response, 0)) {
                Log.w("ExtBridgeConnection", "Daemon does not support liveness verification");
                return null;
            }
            response.readException();
            if (response.dataAvail() < 24) return null;
            int magic = response.readInt();
            int version = response.readInt();
            long echo = response.readLong();
            int pid = response.readInt();
            int uid = response.readInt();
            if (!DaemonProtocol.validProof(magic, version, nonce, echo, pid, uid)) return null;
            String token = "";
            if (version >= 2) {
                if (response.dataAvail() < 4) return null;
                token = response.readString();
                if (token == null || !token.isEmpty() && !token.matches("[a-zA-Z0-9_-]{16,80}")) return null;
            }
            if (response.dataAvail() != 0) return null;
            return new Proof(pid, uid, version, token);
        } catch (Throwable error) {
            Log.w("ExtBridgeConnection", "Daemon liveness verification failed", error);
            return null;
        } finally {
            request.recycle();
            response.recycle();
        }
    }
    public static boolean shutdownOwned(String token) {
        if (token == null || token.isEmpty()) return false;
        IBinder binder = getService();
        if (binder == null) return false;
        Parcel request = Parcel.obtain(); Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(DaemonProtocol.DESCRIPTOR); request.writeString(token);
            if (!binder.transact(DaemonProtocol.OWNED_SHUTDOWN_TRANSACTION, request, reply, 0)) return false;
            reply.readException(); return reply.dataAvail() >= 4 && reply.readInt() == 1;
        } catch (Throwable error) { return false; }
        finally { request.recycle(); reply.recycle(); }
    }
    // END VERIFIED_DAEMON_STATUS

    public static int toggleInput() {
        return simpleCall(10);
    }

    public static int setInputMode(int mode) {
        IBinder b = getService();
        if (b == null) return -1;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(mode);
            b.transact(19, d, r, 0);
            return r.readInt();
        } catch (Throwable t) {
            return -1;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static void sendBack() { simpleOneWay(11); }
    public static void relaunchLauncher() { simpleOneWay(12); }
    public static void sendHome() { simpleOneWay(13); }
    public static void rebuild() { simpleOneWay(21); }

    public static boolean applyConfig(int w, int h, int dpi, float fps, int physModeId) {
        return applyConfig(w, h, dpi, fps, physModeId, -1);
    }

    public static class ApplyResult {
        public boolean ok;
        public int physResult; // 0=ok/unchanged, 1=switched, -1=edid unsupported, -2=invoke failed, -3=system locked physical mode
        public int physW;
        public int physH;
        public float physFps;
    }

    public static boolean applyConfig(int w, int h, int dpi, float fps, int physModeId, int rot) {
        return applyConfigDetailed(w, h, dpi, fps, physModeId, rot).ok;
    }

    public static ApplyResult applyConfigDetailed(int w, int h, int dpi, float fps, int physModeId, int rot) {
        ApplyResult res = new ApplyResult();
        IBinder b = getService();
        if (b == null) return res;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(w);
            d.writeInt(h);
            d.writeInt(dpi);
            d.writeInt(Math.round(fps * 100f));
            d.writeInt(physModeId);
            d.writeInt(rot);
            b.transact(16, d, r, 0);
            res.ok = r.readInt() == 1;
            if (r.dataAvail() >= 16) {
                res.physResult = r.readInt();
                res.physW = r.readInt();
                res.physH = r.readInt();
                res.physFps = r.readInt() / 100f;
            }
            return res;
        } catch (Throwable t) {
            return res;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static boolean shutdownDaemon(android.content.Context ctx) {
        IBinder b = getService();
        if (b != null && verifyDaemon(b) != null) {
            Parcel d = Parcel.obtain();
            Parcel r = Parcel.obtain();
            try {
                b.transact(99, d, r, 0);
            } catch (Throwable ignored) {
            } finally {
                d.recycle();
                r.recycle();
            }
        }
        if (ctx != null) {
            try {
                android.content.Intent i1 = new android.content.Intent("com.extbridge.daemon.SHUTDOWN");
                i1.setPackage("android");
                ctx.sendBroadcast(i1);
                android.content.Intent i2 = new android.content.Intent("com.extbridge.daemon.SHUTDOWN");
                i2.setPackage("com.android.shell");
                ctx.sendBroadcast(i2);
            } catch (Throwable ignored) {}
        }
        if (b == null) return false;
        long deadline = android.os.SystemClock.elapsedRealtime() + 2500;
        while (b.isBinderAlive() && android.os.SystemClock.elapsedRealtime() < deadline) {
            try { Thread.sleep(50); } catch (InterruptedException error) { Thread.currentThread().interrupt(); return false; }
        }
        if (b.isBinderAlive()) return false;
        if (sBinder == b) sBinder = null;
        return true;
    }

    public static int[] sendMouse(int actionType, float a1, float a2) {
        IBinder b = getService();
        if (b == null) return null;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(actionType);
            d.writeFloat(a1);
            d.writeFloat(a2);
            if (!b.transact(17, d, r, 0) || r.dataAvail() < 8) return null;
            int cx = r.readInt();
            int cy = r.readInt();
            int inj = r.dataAvail() >= 4 ? r.readInt() : 1;
            return new int[]{cx, cy, inj};
        } catch (Throwable t) {
            return null;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static void sendKey(int keyCode, int metaState) {
        IBinder b = getService();
        if (b == null) return;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(0);
            d.writeInt(keyCode);
            d.writeInt(metaState);
            d.writeString(null);
            b.transact(18, d, r, 0);
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static void sendAscii(String txt) {
        IBinder b = getService();
        if (b == null) return;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(1);
            d.writeInt(0);
            d.writeInt(0);
            d.writeString(txt);
            b.transact(18, d, r, 0);
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static void sendPaste(boolean useCtrlV) {
        IBinder b = getService();
        if (b == null) return;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(2);
            d.writeInt(useCtrlV ? 1 : 0);
            d.writeInt(0);
            d.writeString(null);
            b.transact(18, d, r, 0);
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static boolean launchComponent(String pkg, String cls) {
        IBinder b = getService();
        if (b == null) return false;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeString(pkg);
            d.writeString(cls);
            return b.transact(20, d, r, 0) && r.dataAvail() >= 4 && r.readInt() == 1;
        } catch (Throwable ignored) {
            return false;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static Bitmap getWallpaperBitmap(boolean force) {
        if (!force && sCachedWpBitmap != null && !sCachedWpBitmap.isRecycled()) {
            return sCachedWpBitmap;
        }
        IBinder b = getService();
        if (b == null) return sCachedWpBitmap;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(force ? 1 : 0);
            if (b.transact(22, d, r, 0)) {
                byte[] bytes = r.createByteArray();
                if (bytes != null && bytes.length > 0) {
                    Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bmp != null) {
                        sCachedWpBitmap = bmp;
                        return bmp;
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
            r.recycle();
        }
        return sCachedWpBitmap;
    }

    public static List<TaskEntry> getTasks() {
        List<TaskEntry> out = new ArrayList<>();
        IBinder b = getService();
        if (b == null) return out;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            if (b.transact(23, d, r, 0)) {
                int n = r.readInt();
                for (int i = 0; i < n && i < 20; i++) {
                    TaskEntry te = new TaskEntry();
                    te.taskId = r.readInt();
                    te.pkg = r.readString();
                    te.flags = r.readInt();
                    if (te.pkg != null && !te.pkg.isEmpty()) out.add(te);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
            r.recycle();
        }
        return out;
    }

    public static boolean switchToTask(int taskId, String pkg) {
        IBinder b = getService();
        if (b == null) return false;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(taskId);
            d.writeString(pkg);
            b.transact(24, d, r, 0);
            return r.readInt() == 1;
        } catch (Throwable ignored) {
            return false;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static boolean minimizeToExtHome(int taskId, String pkg) {
        IBinder b = getService();
        if (b == null) return false;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(taskId);
            d.writeString(pkg);
            b.transact(37, d, r, 0);
            return r.readInt() == 1;
        } catch (Throwable ignored) {
            return false;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static boolean removeTask(int taskId) {
        IBinder b = getService();
        if (b == null) return false;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(taskId);
            b.transact(25, d, r, 0);
            return r.readInt() == 1;
        } catch (Throwable ignored) {
            return false;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static SysStatus getSysStatus() {
        SysStatus st = new SysStatus();
        IBinder b = getService();
        if (b == null) return st;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            if (b.transact(26, d, r, 0)) {
                st.valid = true;
                st.wifiOn = r.readInt() == 1;
                String ws = r.readString();
                st.wifiSsid = ws != null ? ws : "";
                st.btOn = r.readInt() == 1;
                String bn = r.readString();
                st.btName = bn != null ? bn : "";
                st.airplaneOn = r.readInt() == 1;
                st.powerSaveOn = r.readInt() == 1;
                st.screenDimmed = (r.dataAvail() >= 4) && (r.readInt() == 1);
            }
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
            r.recycle();
        }
        return st;
    }

    public static boolean execSysAction(int act, int arg, String str) {
        IBinder b = getService();
        if (b == null) return false;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(act);
            d.writeInt(arg);
            d.writeString(str);
            b.transact(27, d, r, 0);
            return r.readInt() == 1;
        } catch (Throwable ignored) {
            return false;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static final int MIRROR_MODE_DEX = 0;
    public static final int MIRROR_MODE_MIRROR = 1;

    public static String mirrorModeLabel(int mode) {
        return mode == MIRROR_MODE_MIRROR ? "原生镜像" : "DeX 扩展桌面";
    }

    public static int setMirrorMode(int mode) {
        IBinder b = getService();
        if (b == null) return -1;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(mode);
            b.transact(28, d, r, 0);
            return r.readInt();
        } catch (Throwable ignored) {
            return -1;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static int toggleMirrorMode() {
        return setMirrorMode(-1);
    }

    public static int setFullscreenPolicy(int enable) {
        IBinder b = getService();
        if (b == null) return -1;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(enable);
            b.transact(29, d, r, 0);
            return r.readInt();
        } catch (Throwable ignored) {
            return -1;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static int toggleFullscreenPolicy() {
        return setFullscreenPolicy(-1);
    }

    public static int setRotation(int rot) {
        IBinder b = getService();
        if (b == null) return -1;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(rot);
            b.transact(34, d, r, 0);
            return r.readInt();
        } catch (Throwable ignored) {
            return -1;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static int cycleRotation() {
        return setRotation(-1);
    }

    public static String rotationLabel(int rot) {
        int r = ((rot % 4) + 4) % 4;
        switch (r) {
            case 1: return "90° 竖屏";
            case 2: return "180° 倒置";
            case 3: return "270° 竖屏";
            default: return "0° 横屏";
        }
    }

    public static final int APP_MODE_DEFAULT = 0;
    public static final int APP_MODE_LAND_FULL = 1;
    public static final int APP_MODE_PORT_COMPAT = 2;
    public static final int APP_MODE_FREEFORM = 3;

    public static String appModeLabel(int mode) {
        switch (mode) {
            case APP_MODE_LAND_FULL: return "强制横屏全屏";
            case APP_MODE_PORT_COMPAT: return "竖屏居中兼容";
            default: return "跟随系统默认";
        }
    }

    public static String appModeBadge(int mode) {
        switch (mode) {
            case APP_MODE_LAND_FULL: return "横屏全屏";
            case APP_MODE_PORT_COMPAT: return "竖屏兼容";
            default: return "";
        }
    }

    public static int prepareAppLaunch(String pkg) {
        if (pkg == null || pkg.isEmpty()) return 0;
        IBinder b = getService();
        if (b == null) return 0;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeString(pkg);
            b.transact(30, d, r, 0);
            return r.readInt();
        } catch (Throwable ignored) {
            return 0;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static Map<String, Integer> getAllAppModes() {
        Map<String, Integer> map = new HashMap<>();
        IBinder b = getService();
        if (b == null) return map;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            b.transact(31, d, r, 0);
            String s = r.readString();
            if (s != null && !s.isEmpty()) {
                for (String item : s.split(";")) {
                    int eq = item.indexOf('=');
                    if (eq > 0 && eq < item.length() - 1) {
                        String pkg = item.substring(0, eq).trim();
                        int mode = Integer.parseInt(item.substring(eq + 1).trim());
                        if (!pkg.isEmpty() && mode >= 0 && mode <= 3) {
                            map.put(pkg, mode);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
            r.recycle();
        }
        return map;
    }

    public static boolean setAppMode(String pkg, int mode, boolean restart) {
        if (pkg == null || pkg.isEmpty()) return false;
        IBinder b = getService();
        if (b == null) return false;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeString(pkg);
            d.writeInt(mode);
            d.writeInt(restart ? 1 : 0);
            b.transact(32, d, r, 0);
            return r.readInt() == 1;
        } catch (Throwable ignored) {
            return false;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static boolean resetAllAppModes() {
        return simpleCall(33) == 1;
    }

    public static final int SPLIT_ASSIGN_LEFT_TOP = 1;
    public static final int SPLIT_ASSIGN_RIGHT_BOTTOM = 2;
    public static final int SPLIT_TOGGLE_QUICK = 3;
    public static final int SPLIT_SET_RATIO = 4;
    public static final int SPLIT_SWAP = 5;
    public static final int SPLIT_EXIT = 6;

    public static boolean splitAction(int action, int arg, String pkg, String cls) {
        IBinder b = getService();
        if (b == null) return false;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(action);
            d.writeInt(arg);
            d.writeString(pkg != null ? pkg : "");
            d.writeString(cls != null ? cls : "");
            b.transact(35, d, r, 0);
            return r.readInt() == 1;
        } catch (Throwable ignored) {
            return false;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public static boolean assignSplitSlot(int slot, String pkg, String cls) {
        return splitAction(slot == 0 ? SPLIT_ASSIGN_LEFT_TOP : SPLIT_ASSIGN_RIGHT_BOTTOM, -1, pkg, cls);
    }

    public static boolean toggleQuickSplit() {
        return splitAction(SPLIT_TOGGLE_QUICK, 0, null, null);
    }

    public static boolean setSplitRatio(int ratioPct) {
        return splitAction(SPLIT_SET_RATIO, Math.max(25, Math.min(75, ratioPct)), null, null);
    }

    public static boolean swapSplitSlots() {
        return splitAction(SPLIT_SWAP, 0, null, null);
    }

    public static boolean exitSplitMode() {
        return splitAction(SPLIT_EXIT, 0, null, null);
    }

    /** Switches a task between floating freeform window (1) and fullscreen (0); -1 toggles. */
    public static int toggleTaskFreeform(int taskId, String pkg, int enableFreeform) {
        IBinder b = getService();
        if (b == null) return -1;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInt(taskId);
            d.writeString(pkg != null ? pkg : "");
            d.writeInt(enableFreeform);
            b.transact(36, d, r, 0);
            return r.readInt();
        } catch (Throwable ignored) {
            return -1;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    private static int simpleCall(int code) {
        IBinder b = getService();
        if (b == null) return -1;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            b.transact(code, d, r, 0);
            return r.readInt();
        } catch (Throwable t) {
            return -1;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    private static void simpleOneWay(int code) {
        IBinder b = getService();
        if (b == null) return;
        Parcel d = Parcel.obtain();
        try {
            b.transact(code, d, null, IBinder.FLAG_ONEWAY);
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
        }
    }
}
