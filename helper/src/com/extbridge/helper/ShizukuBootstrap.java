package com.extbridge.helper;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * Privileged shell bootstrap integration via Shizuku / Sui (dev.rikka.shizuku:api:13.1.5).
 * Coexists alongside built-in Wireless Debugging (libadb.so), Root (su), and PC ADB.
 */
public final class ShizukuBootstrap {
    private static final String TAG = "ExtBridgeShizuku";
    public static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    public static final String SHIZUKU_WEBSITE = "https://shizuku.rikka.app/";
    public static final int REQUEST_CODE_PERMISSION = 3908;

    private static final Handler sMain = new Handler(Looper.getMainLooper());
    private static boolean sListenersAttached = false;
    private static volatile Runnable sStateObserver;
    private static volatile PermissionResultCallback sPermCallback;

    public interface PermissionResultCallback {
        void onPermissionResult(boolean granted);
    }

    public static final class ShellResult {
        public final int code;
        public final String output;

        public ShellResult(int code, String output) {
            this.code = code;
            this.output = output == null ? "" : output;
        }
    }

    private static final Shizuku.OnBinderReceivedListener sBinderReceivedListener = () -> {
        final Runnable obs = sStateObserver;
        if (obs != null) sMain.post(obs);
    };

    private static final Shizuku.OnBinderDeadListener sBinderDeadListener = () -> {
        final Runnable obs = sStateObserver;
        if (obs != null) sMain.post(obs);
    };

    private static final Shizuku.OnRequestPermissionResultListener sPermResultListener = (requestCode, grantResult) -> {
        if (requestCode != REQUEST_CODE_PERMISSION) return;
        final boolean granted = (grantResult == PackageManager.PERMISSION_GRANTED);
        sMain.post(() -> {
            final Runnable obs = sStateObserver;
            if (obs != null) obs.run();
            final PermissionResultCallback cb = sPermCallback;
            if (cb != null) cb.onPermissionResult(granted);
        });
    };

    private ShizukuBootstrap() {}

    public static synchronized void bindUiCallbacks(Runnable stateObserver, PermissionResultCallback permCallback) {
        sStateObserver = stateObserver;
        sPermCallback = permCallback;
        if (!sListenersAttached) {
            try {
                Shizuku.addBinderReceivedListenerSticky(sBinderReceivedListener);
                Shizuku.addBinderDeadListener(sBinderDeadListener);
                Shizuku.addRequestPermissionResultListener(sPermResultListener);
                sListenersAttached = true;
            } catch (Throwable t) {
                Log.w(TAG, "Failed to attach Shizuku listeners", t);
            }
        } else if (stateObserver != null) {
            sMain.post(stateObserver);
        }
    }

    public static synchronized void unbindUiCallbacks() {
        sStateObserver = null;
        sPermCallback = null;
    }

    public static boolean isShizukuInstalled(Context ctx) {
        if (ctx == null) return false;
        try {
            ctx.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isBinderAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isPreV11() {
        try {
            return Shizuku.pingBinder() && Shizuku.isPreV11();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isPermissionGranted() {
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return false;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isReadyAndGranted() {
        return isBinderAlive() && isPermissionGranted();
    }

    public static boolean shouldShowRationale() {
        try {
            return Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.shouldShowRequestPermissionRationale();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static int getRemoteUid() {
        try {
            return Shizuku.pingBinder() ? Shizuku.getUid() : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public static String buildStateSummary(Context ctx) {
        if (isBinderAlive()) {
            if (isPreV11()) {
                return "当前 Shizuku 版本过低（低于 v11），请升级 Shizuku 后重试。";
            }
            int uid = getRemoteUid();
            String mode = (uid == 0) ? "Root / Sui 模式" : ("ADB Shell 模式 · uid=" + (uid > 0 ? uid : 2000));
            if (isPermissionGranted()) {
                return "Shizuku 服务运行中（" + mode + "）· 已授权，可免配对码一键激活";
            }
            if (shouldShowRationale()) {
                return "Shizuku 服务运行中（" + mode + "）· 曾在弹窗中设为不再询问，请打开 Shizuku 手动勾选 ExtBridge";
            }
            return "Shizuku 服务运行中（" + mode + "）· 点击下方按钮授权后自动激活";
        }
        if (isShizukuInstalled(ctx)) {
            return "已安装 Shizuku，但后台服务尚未启动。点击「打开 Shizuku」启动后再返回即可一键激活。";
        }
        return "未检测到 Shizuku / Sui 服务。若设备已常驻 Shizuku 可免输配对码秒级激活；未安装也可直接使用左侧「无线调试」。";
    }

    public static boolean requestPermissionIfPossible() {
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return false;
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true;
            if (Shizuku.shouldShowRequestPermissionRationale()) return false;
            Shizuku.requestPermission(REQUEST_CODE_PERMISSION);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "requestPermission failed", t);
            return false;
        }
    }

    public static boolean openShizukuAppOrWebsite(Context ctx) {
        if (ctx == null) return false;
        try {
            Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(SHIZUKU_PACKAGE);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                launchOnDisplay0(ctx, launch);
                return true;
            }
        } catch (Throwable ignored) {}
        try {
            Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_WEBSITE));
            web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            launchOnDisplay0(ctx, web);
        } catch (Throwable ignored) {}
        return false;
    }

    private static void launchOnDisplay0(Context ctx, Intent intent) {
        try {
            ActivityOptions opts = ActivityOptions.makeBasic().setLaunchDisplayId(0);
            ctx.startActivity(intent, opts.toBundle());
        } catch (Throwable ignored) {
            ctx.startActivity(intent);
        }
    }

    public static ShellResult execShell(long timeoutMs, byte[] stdinBytes, String shellCmd) {
        IRemoteProcess proc = null;
        ParcelFileDescriptor pfdIn = null;
        ParcelFileDescriptor pfdOut = null;
        ParcelFileDescriptor pfdErr = null;
        try {
            IBinder binder = Shizuku.getBinder();
            if (binder == null || !binder.pingBinder()) {
                return new ShellResult(-1, "Shizuku 服务未运行或连接已断开");
            }
            IShizukuService service = IShizukuService.Stub.asInterface(binder);
            if (service == null) {
                return new ShellResult(-1, "无法获取 Shizuku IShizukuService 接口");
            }
            proc = service.newProcess(
                    new String[]{"/system/bin/sh", "-c", shellCmd},
                    null,
                    "/data/local/tmp"
            );
            if (proc == null) {
                return new ShellResult(-1, "Shizuku 创建远程 Shell 进程失败");
            }

            pfdIn = proc.getOutputStream();
            pfdOut = proc.getInputStream();
            pfdErr = proc.getErrorStream();

            if (pfdIn != null) {
                if (stdinBytes != null && stdinBytes.length > 0) {
                    final ParcelFileDescriptor stdinRef = pfdIn;
                    pfdIn = null; // Ownership transferred to AutoCloseOutputStream
                    Thread writer = new Thread(() -> {
                        try (OutputStream os = new ParcelFileDescriptor.AutoCloseOutputStream(stdinRef)) {
                            int off = 0;
                            while (off < stdinBytes.length) {
                                int chunk = Math.min(8192, stdinBytes.length - off);
                                os.write(stdinBytes, off, chunk);
                                off += chunk;
                            }
                            os.flush();
                        } catch (Throwable ignored) {}
                    }, "ExtShizukuIn");
                    writer.setDaemon(true);
                    writer.start();
                } else {
                    try { pfdIn.close(); } catch (Throwable ignored) {}
                    pfdIn = null;
                }
            }

            final StringBuilder outBuf = new StringBuilder();
            final StringBuilder errBuf = new StringBuilder();

            Thread outReader = null;
            if (pfdOut != null) {
                final ParcelFileDescriptor outRef = pfdOut;
                outReader = new Thread(() -> {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(outRef)))) {
                        String ln;
                        while ((ln = br.readLine()) != null) {
                            if (outBuf.length() < 8192) {
                                if (outBuf.length() > 0) outBuf.append('\n');
                                outBuf.append(ln);
                            }
                        }
                    } catch (Throwable ignored) {}
                }, "ExtShizukuOut");
                outReader.setDaemon(true);
                outReader.start();
            }

            Thread errReader = null;
            if (pfdErr != null) {
                final ParcelFileDescriptor errRef = pfdErr;
                errReader = new Thread(() -> {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(errRef)))) {
                        String ln;
                        while ((ln = br.readLine()) != null) {
                            if (errBuf.length() < 4096) {
                                if (errBuf.length() > 0) errBuf.append('\n');
                                errBuf.append(ln);
                            }
                        }
                    } catch (Throwable ignored) {}
                }, "ExtShizukuErr");
                errReader.setDaemon(true);
                errReader.start();
            }

            final IRemoteProcess procRef = proc;
            final AtomicInteger exitHolder = new AtomicInteger(-1);
            final CountDownLatch latch = new CountDownLatch(1);
            Thread waiter = new Thread(() -> {
                try {
                    exitHolder.set(procRef.waitFor());
                } catch (Throwable ignored) {
                } finally {
                    latch.countDown();
                }
            }, "ExtShizukuWait");
            waiter.setDaemon(true);
            waiter.start();

            boolean finished = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                try { proc.destroy(); } catch (Throwable ignored) {}
                closeQuietly(pfdOut);
                closeQuietly(pfdErr);
                if (outReader != null) outReader.join(300);
                if (errReader != null) errReader.join(300);
                return new ShellResult(-1, "Shizuku Shell 执行超时 (" + timeoutMs + "ms)\n" + mergeOutErr(outBuf, errBuf));
            }

            // Wait briefly for stdout/stderr pipes to flush after sh exits, then force-close FDs in case any background child inherited them
            if (outReader != null) outReader.join(700);
            if (errReader != null) errReader.join(700);
            closeQuietly(pfdOut);
            closeQuietly(pfdErr);

            return new ShellResult(exitHolder.get(), mergeOutErr(outBuf, errBuf));
        } catch (Throwable t) {
            Log.e(TAG, "execShell failed", t);
            closeQuietly(pfdIn);
            closeQuietly(pfdOut);
            closeQuietly(pfdErr);
            if (proc != null) {
                try { proc.destroy(); } catch (Throwable ignored) {}
            }
            return new ShellResult(-1, "Shizuku 执行异常: " + t.getMessage());
        }
    }

    private static String mergeOutErr(StringBuilder out, StringBuilder err) {
        String sOut = out.toString().trim();
        String sErr = err.toString().trim();
        if (sOut.isEmpty()) return sErr;
        if (sErr.isEmpty()) return sOut;
        return sOut + "\n" + sErr;
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        if (pfd != null) {
            try { pfd.close(); } catch (Throwable ignored) {}
        }
    }
}
