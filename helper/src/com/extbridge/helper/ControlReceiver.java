package com.extbridge.helper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ControlReceiver extends BroadcastReceiver {
    private static final ExecutorService sExec = Executors.newSingleThreadExecutor();
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private static void toastAsync(Context appCtx, String msg) {
        if (appCtx == null || msg == null) return;
        sMain.post(() -> Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show());
    }

    /**
     * Non-exported receiver used exclusively by in-app Notification PendingIntents so internal
     * actions never need exported broadcast permissions.
     */
    public static class Internal extends ControlReceiver {}

    private boolean isTrustedBroadcastSender(Context context) {
        if (this instanceof Internal) {
            return true;
        }
        // Exported ControlReceiver is enforced by android:permission="android.permission.INJECT_EVENTS"
        // (pure signature permission held by com.android.shell, impossible to grant to 3rd-party apps).
        // The official signing certificate is pinned on the daemon side (getVerifiedHelperUid)
        // and at release-build time in helper/build.gradle.kts.
        // On Android 14+, when sender identity is explicitly shared (uid != INVALID_UID), also
        // enforce the strict UID allowlist.
        if (Build.VERSION.SDK_INT >= 34) {
            int uid = getSentFromUid();
            if (uid != android.os.Process.INVALID_UID) {
                return uid == 0 || uid == 1000 || uid == 2000 || uid == android.os.Process.myUid();
            }
        }
        return true;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        final String act = intent.getAction();
        if (act == null) return;
        if (!isTrustedBroadcastSender(context)) {
            return;
        }

        if ("com.extbridge.helper.BINDER_PUSH".equals(act)) {
            if (this instanceof Internal || BridgeService.sUserStopping) return;
            if (Build.VERSION.SDK_INT >= 34) {
                int uid = getSentFromUid();
                if (uid != android.os.Process.INVALID_UID && uid != 0 && uid != 1000 && uid != 2000) return;
            }
            android.os.Bundle extras = intent.getExtras();
            android.os.IBinder cb = (extras != null) ? extras.getBinder("cb") : null;
            if (cb != null && cb.pingBinder()) {
                try {
                    if (DaemonProtocol.DESCRIPTOR.equals(cb.getInterfaceDescriptor())) {
                        BridgeClient.setBinder(cb);
                        BridgeService.ensureStarted(context);
                        BridgeService.refreshFromReceiver(context);
                    }
                } catch (Throwable ignored) {}
            }
            return;
        }

        if ("com.extbridge.helper.STATE_SYNC".equals(act)) {
            BridgeService.refreshFromReceiver(context);
            return;
        }
        if (BridgeService.ACTION_EXIT_ALL.equals(act)) {
            Toast.makeText(context, "正在停止 ExtBridge 后台服务并退出…", Toast.LENGTH_SHORT).show();
            BridgeService.stopAllAndExit(context);
            return;
        }
        if ("com.extbridge.helper.SET_DOCK_AUTO_HIDE".equals(act)) {
            boolean defVal = !BridgeService.isDockAutoHideEnabled(context);
            boolean en = intent.hasExtra("auto_hide")
                    ? intent.getBooleanExtra("auto_hide", defVal)
                    : intent.getBooleanExtra("enable", defVal);
            BridgeService.setDockAutoHideEnabled(context, en);
            return;
        }

        final Context appCtx = context.getApplicationContext();
        final Intent copy = new Intent(intent);
        final PendingResult pr = goAsync();
        sExec.execute(() -> {
            try {
                if ("com.extbridge.helper.TOGGLE_INPUT".equals(act)) {
                    int mode = BridgeClient.toggleInput();
                    if (mode >= 0) {
                        toastAsync(appCtx, mode == 1 ? "键鼠已切换至：扩展屏" : "键鼠已切换至：平板内屏");
                    }
                    BridgeService.refreshFromReceiver(appCtx);
                } else if ("com.extbridge.helper.REBUILD".equals(act)) {
                    BridgeClient.rebuild();
                    toastAsync(appCtx, "正在刷新扩展屏输出...");
                } else if ("com.extbridge.helper.LAUNCH_EXT_HOME".equals(act)) {
                    BridgeClient.relaunchLauncher();
                    toastAsync(appCtx, "已返回扩展屏桌面");
                } else if ("com.extbridge.helper.TOGGLE_MIRROR".equals(act)) {
                    int mode = copy.getIntExtra("mode", -1);
                    int next = BridgeClient.setMirrorMode(mode);
                    BridgeService.refreshFromReceiver(appCtx);
                    toastAsync(appCtx, next == 1 ? "已切换为【原生镜像模式】" : "已切换为【ExtBridge DeX 扩展模式】");
                } else if ("com.extbridge.helper.SPLIT_ACTION".equals(act)) {
                    int splitAct = copy.getIntExtra("action", BridgeClient.SPLIT_TOGGLE_QUICK);
                    int arg = copy.getIntExtra("arg", 0);
                    String pkg = copy.getStringExtra("pkg");
                    String cls = copy.getStringExtra("cls");
                    BridgeClient.splitAction(splitAct, arg, pkg, cls);
                    BridgeService.refreshFromReceiver(appCtx);
                } else if ("com.extbridge.helper.SET_ROTATION".equals(act)) {
                    int rot = copy.hasExtra("rot")
                            ? copy.getIntExtra("rot", -1)
                            : copy.getIntExtra("rotation", -1);
                    if (rot == 90) rot = 1;
                    else if (rot == 180) rot = 2;
                    else if (rot == 270) rot = 3;
                    if (rot >= 0) BridgeClient.setRotation(rot);
                    else BridgeClient.cycleRotation();
                    BridgeService.refreshFromReceiver(appCtx);
                }
            } catch (Throwable ignored) {
            } finally {
                pr.finish();
            }
        });
    }
}
