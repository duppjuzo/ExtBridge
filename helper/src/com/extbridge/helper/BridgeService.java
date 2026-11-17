package com.extbridge.helper;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.net.TrafficStats;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class BridgeService extends Service {
    public static final String ACTION_EXIT_ALL = "com.extbridge.helper.EXIT_ALL";
    private static final String CH_ID = "extbridge_ctrl_v3";
    private static final int NOTIF_ID = 301;
    private static volatile BridgeService sInstance;
    public static volatile boolean sUserStopping = false;

    public static boolean isRunning() {
        return sInstance != null;
    }
    private static ViewGroup sLauncherHost;
    private static int sLauncherHostDisplayId = -1;

    private Handler mHandler;
    private final ExecutorService mBg = Executors.newSingleThreadExecutor();
    private volatile boolean mDestroyed = false;
    private final AtomicLong mPollSeq = new AtomicLong(0);
    private String mLastNotifSig = "";
    private NotificationManager mNm;
    private DisplayManager mDm;
    private AudioManager mAm;

    private int mAttachedVdId = -1;
    private int mOverlayW = 0;
    private int mOverlayH = 0;
    private int mOverlayDpi = 0;
    private Context mExtCtx;
    private WindowManager mExtWm;
    private boolean mUsingA11yOverlay = false;
    private boolean mInLauncherHost = false;
    private boolean mLastObservedA11y = false;
    private int mWinType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

    // Dock window (macOS style centered frosted glass)
    private static final String PREF_NAME = "ext_prefs";
    private static final String PREF_DOCK_AUTO_HIDE = "dock_auto_hide";
    private static final long DOCK_AUTO_HIDE_DELAY_MS = 2600L;

    private LinearLayout mDockRoot;
    private WindowManager.LayoutParams mDockLp;
    private boolean mDockHidden = false;
    private boolean mDockAutoHide = true;
    private boolean mAutoHideScheduled = false;
    private boolean mIsOnLauncherHome = true;
    private boolean mDockHovered = false;
    private FrameLayout mDockEdgeTrigger;
    private View mDockEdgePill;
    private WindowManager.LayoutParams mDockEdgeLp;
    private TextView mBtnDockSplit;
    private TextView mBtnToggle;
    private LinearLayout mTaskStrip;
    private View mTaskDivider;
    private LinearLayout mTrayBtn;
    private MiniIconView mTrayWifiIcon;
    private MiniIconView mTrayVolIcon;
    private MiniBatteryView mTrayBattView;
    private TextView mTrayBattPct;

    // Split-screen floating divider & popover menu
    private FrameLayout mSplitDividerRoot;
    private TextView mSplitDividerPill;
    private WindowManager.LayoutParams mSplitDividerLp;
    private boolean mSplitDividerPortrait = false;
    private boolean mSplitDragging = false;
    private LinearLayout mSplitMenuRoot;
    private WindowManager.LayoutParams mSplitMenuLp;
    private boolean mSplitMenuOpen = false;
    private TextView mSplitSwapBtn;
    private final TextView[] mSplitRatioBtns = new TextView[5];
    private static final int[] SPLIT_PRESET_PCTS = {30, 40, 50, 60, 70};

    // Dock app context bubble (Android long-press / right-click shortcut popup)
    private LinearLayout mTaskBubbleRoot;
    private LinearLayout mTaskBubbleCard;
    private View mBubbleTriangle;
    private WindowManager.LayoutParams mTaskBubbleLp;
    private boolean mTaskBubbleOpen = false;
    private long mLastBubbleOpenMs = 0L;
    private ImageView mBubbleIcon;
    private TextView mBubbleTitle;
    private TextView mBubbleModeTag;
    private TextView mBubbleFreeformBtn;
    private TextView mBubbleSplit0Btn;
    private TextView mBubbleSplit1Btn;
    private TextView mBubbleCloseBtn;
    private BridgeClient.TaskEntry mBubbleTask;
    private String mBubbleLabel = "";
    private int mBubbleAnchorCenterX = 0;

    // Win11 Quick Settings Flyout window (no brightness slider)
    private FrameLayout mPanelRoot;
    private LinearLayout mPanelCard;
    private WindowManager.LayoutParams mPanelLp;
    private boolean mPanelOpen = false;
    private boolean mTrayPressed = false;
    private final Runnable mAutoHideDockTask = () -> {
        mAutoHideScheduled = false;
        if (mDockAutoHide && !mIsOnLauncherHome && !mPanelOpen && !mSplitMenuOpen && !mDockHovered && !mTrayPressed) {
            applyDockHiddenAnimated(true);
        }
    };
    private long mLastTrayUpMs = 0L;
    private long mLastTrayToggleMs = 0L;
    private final Runnable mOutsideDismissTask = () -> {
        long now = SystemClock.uptimeMillis();
        if (mPanelOpen && !mTrayPressed
                && (now - mLastTrayUpMs) > 220L
                && (now - mLastTrayToggleMs) > 220L) {
            setPanelOpen(false);
        }
    };
    private QuickTile mTileWifi;
    private QuickTile mTileBt;
    private QuickTile mTileInput;
    private QuickTile mTileAirplane;
    private QuickTile mTileTouchpad;
    private QuickTile mTilePowerSave;
    private MiniIconView mVolSpeakerIcon;
    private SeekBar mVolSeekBar;
    private boolean mSeekingVol = false;
    private MiniBatteryView mPanelBattView;
    private TextView mPanelBattText;
    private TextView mBtnPanelFullscreen;
    private TextView mBtnPanelDex;
    private TextView mBtnPanelMirror;
    private TextView mBtnPanelRotate;
    private TextView mBtnPanelSplit;
    private TextView mBtnPanelHideDock;
    private TextView mBtnPanelBlackout;
    private FrameLayout mBlackoutView;
    private WindowManager mBlackoutWm;
    private Runnable mBlackoutHideHintTask;
    private volatile boolean mBlackoutActive = false;
    private long mLastBlackoutTapMs = 0L;

    // Software mouse cursor window (always topmost)
    private CursorView mCursorView;
    private WindowManager.LayoutParams mCursorLp;
    private boolean mCursorVisible = false;

    private final LruCache<String, Drawable> mIconCache = new LruCache<>(48);
    private final LruCache<String, String> mLabelCache = new LruCache<>(64);
    private String mLastTaskSig = "";
    private volatile BridgeClient.Status mLastStatus = new BridgeClient.Status();
    private volatile BridgeClient.SysStatus mLastSys = new BridgeClient.SysStatus();
    private int mBattPct = 100;
    private boolean mBattCharging = false;

    private final BroadcastReceiver mSysRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String act = intent.getAction();
            if (Intent.ACTION_BATTERY_CHANGED.equals(act)) {
                readBatteryIntent(intent);
            }
            if (mHandler != null) {
                mHandler.post(() -> {
                    refreshLocalHardwareViews();
                    if (!Intent.ACTION_BATTERY_CHANGED.equals(act)) {
                        pollRemoteStateAsync();
                    }
                });
            }
        }
    };

    private void safeBgExecute(Runnable r) {
        if (mDestroyed || r == null || mBg.isShutdown()) return;
        try {
            mBg.execute(() -> {
                if (!mDestroyed) r.run();
            });
        } catch (RejectedExecutionException ignored) {}
    }

    private void safePost(Runnable r) {
        if (mDestroyed || mHandler == null || r == null) return;
        mHandler.post(() -> {
            if (!mDestroyed) r.run();
        });
    }

    private final Runnable mHideCursorTask = () -> setCursorVisible(false);
    private final Runnable mPollTask = new Runnable() {
        @Override
        public void run() {
            if (mDestroyed || mHandler == null) return;
            pollRemoteStateAsync();
            BridgeClient.Status st = mLastStatus;
            long delay = (st != null && st.connected && (st.vdId > 0 || st.mirrorMode == 1)) ? 2200L : 6000L;
            mHandler.postDelayed(this, delay);
        }
    };

    public static boolean isDockAutoHideEnabled(Context ctx) {
        if (sInstance != null) return sInstance.mDockAutoHide;
        if (ctx == null) return true;
        return ctx.getSharedPreferences(PREF_NAME, MODE_PRIVATE).getBoolean(PREF_DOCK_AUTO_HIDE, true);
    }

    public static void setDockAutoHideEnabled(Context ctx, boolean enabled) {
        if (ctx != null) {
            ctx.getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putBoolean(PREF_DOCK_AUTO_HIDE, enabled).apply();
        }
        BridgeService svc = sInstance;
        if (svc == null) return;
        Runnable r = () -> {
            svc.mDockAutoHide = enabled;
            svc.updateDockAutoHideUi();
            if (!enabled) {
                svc.mHandler.removeCallbacks(svc.mAutoHideDockTask);
                svc.applyDockHiddenAnimated(false);
            } else {
                svc.evaluateDockAutoHide(false);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else if (svc.mHandler != null) svc.mHandler.post(r);
    }

    public static void ensureStarted(Context ctx) {
        if (sUserStopping || ctx == null) return;
        try {
            Intent i = new Intent(ctx, BridgeService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable ignored) {}
    }

    public static void refreshFromReceiver(Context ctx) {
        if (sUserStopping) return;
        BridgeService svc = sInstance;
        if (svc != null && svc.mHandler != null) {
            svc.mHandler.post(svc::pollRemoteStateAsync);
        }
    }

    public static void stopAllAndExit(Context ctx) {
        sUserStopping = true;
        final Context appCtx = (ctx != null) ? ctx.getApplicationContext() : sInstance;
        final BridgeService svc = sInstance;
        if (svc != null && svc.mHandler != null) {
            svc.mHandler.removeCallbacksAndMessages(null);
            svc.mHandler.post(() -> {
                try { svc.detachExtOverlays(false); } catch (Throwable ignored) {}
                try {
                    if (svc.mNm != null) svc.mNm.cancel(NOTIF_ID);
                } catch (Throwable ignored) {}
                try {
                    if (Build.VERSION.SDK_INT >= 24) {
                        svc.stopForeground(STOP_FOREGROUND_REMOVE);
                    } else {
                        svc.stopForeground(true);
                    }
                } catch (Throwable ignored) {}
            });
        } else if (appCtx != null) {
            try {
                NotificationManager nm = (NotificationManager) appCtx.getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(NOTIF_ID);
            } catch (Throwable ignored) {}
        }

        if (appCtx != null) {
            try {
                android.app.ActivityManager am = (android.app.ActivityManager) appCtx.getSystemService(ACTIVITY_SERVICE);
                if (am != null) {
                    List<android.app.ActivityManager.AppTask> tasks = am.getAppTasks();
                    if (tasks != null) {
                        for (android.app.ActivityManager.AppTask t : tasks) {
                            try { t.finishAndRemoveTask(); } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        new Thread(() -> {
            try {
                for (int i = 0; i < 8 && BridgeClient.getService() == null; i++) {
                    Thread.sleep(200);
                }
                BridgeClient.shutdownDaemon(appCtx);
            } catch (Throwable ignored) {}
            try { Thread.sleep(160); } catch (Throwable ignored) {}
            if (svc != null) {
                try { svc.stopSelf(); } catch (Throwable ignored) {}
            }
            try { Thread.sleep(80); } catch (Throwable ignored) {}
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        }, "ExtBridgeExit").start();
    }

    public static void moveCursor(float x, float y, boolean pressing) {
        BridgeService svc = sInstance;
        if (svc == null) return;
        if (Looper.myLooper() == svc.mHandler.getLooper()) {
            svc.updateCursorOnExt(x, y, pressing);
        } else {
            svc.mHandler.post(() -> svc.updateCursorOnExt(x, y, pressing));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mDockAutoHide = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getBoolean(PREF_DOCK_AUTO_HIDE, true);
        mHandler = new Handler(Looper.getMainLooper());
        mNm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        mDm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        mAm = (AudioManager) getSystemService(AUDIO_SERVICE);

        createNotificationChannel();
        Notification n = buildNotification(BridgeClient.getStatus());
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }

        IntentFilter flt = new IntentFilter();
        flt.addAction(Intent.ACTION_BATTERY_CHANGED);
        flt.addAction("android.media.VOLUME_CHANGED_ACTION");
        flt.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        flt.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        Intent sticky = registerReceiver(mSysRx, flt, RECEIVER_EXPORTED);
        if (sticky != null) readBatteryIntent(sticky);

        NavAssistService.setOnStateChanged(() -> safePost(() -> {
            detachExtOverlays(false);
            pollRemoteStateAsync();
        }));

        mHandler.post(mPollTask);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (sUserStopping || (intent != null && ACTION_EXIT_ALL.equals(intent.getAction()))) {
            stopAllAndExit(this);
            return START_NOT_STICKY;
        }
        if (intent != null && intent.getExtras() != null) {
            IBinder cb = intent.getExtras().getBinder("cb");
            if (cb != null) BridgeClient.setBinder(cb);
        }
        if (BridgeClient.getService() == null) {
            BridgeClient.requestBinder(this);
        }
        pollRemoteStateAsync();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        mDestroyed = true;
        sInstance = null;
        NavAssistService.setOnStateChanged(null);
        try { unregisterReceiver(mSysRx); } catch (Throwable ignored) {}
        if (mHandler != null) mHandler.removeCallbacksAndMessages(null);
        if (mBlackoutActive) {
            mBlackoutActive = false;
            try {
                new Thread(() -> BridgeClient.execSysAction(BridgeClient.SYS_SCREEN_DIM, 0, null),
                        "ExtDimRestore").start();
            } catch (Throwable ignored) {}
        }
        removeBlackoutOverlay();
        detachExtOverlays(false);
        mBg.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void readBatteryIntent(Intent bi) {
        int lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        if (lvl >= 0 && scale > 0) {
            mBattPct = Math.max(0, Math.min(100, Math.round(lvl * 100f / scale)));
        }
        mBattCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH_ID, "ExtBridge 外接屏控制", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("通知栏一键切换平板/扩展屏键鼠、进入大触摸板及分辨率控制台");
            ch.setShowBadge(false);
            mNm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(BridgeClient.Status st) {
        ActivityOptions opts0 = ActivityOptions.makeBasic();
        opts0.setLaunchDisplayId(0);
        Bundle optBundle0 = opts0.toBundle();

        Intent mainIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent piMain = PendingIntent.getActivity(this, 1, mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE, optBundle0);

        Intent tpIntent = new Intent(this, TouchpadActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent piTp = PendingIntent.getActivity(this, 2, tpIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE, optBundle0);

        Intent toggleIntent = new Intent(this, ControlReceiver.class)
                .setAction("com.extbridge.helper.TOGGLE_INPUT");
        PendingIntent piToggle = PendingIntent.getBroadcast(this, 3, toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent extHomeIntent = new Intent(this, ControlReceiver.class)
                .setAction("com.extbridge.helper.LAUNCH_EXT_HOME");
        PendingIntent piExtHome = PendingIntent.getBroadcast(this, 4, extHomeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent mirrorIntent = new Intent(this, ControlReceiver.class)
                .setAction("com.extbridge.helper.TOGGLE_MIRROR")
                .putExtra("mode", 0);
        PendingIntent piMirror = PendingIntent.getBroadcast(this, 5, mirrorIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent exitIntent = new Intent(this, ControlReceiver.class)
                .setAction(ACTION_EXIT_ALL);
        PendingIntent piExit = PendingIntent.getBroadcast(this, 6, exitIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        boolean mirrorActive = st.connected && st.mirrorMode == 1 && st.extId > 0;
        boolean standbyOrDisconnected = !st.connected || (!mirrorActive && st.vdId <= 0);
        String title;
        String body;
        String toggleLabel;
        if (!st.connected) {
            title = "ExtBridge 未连接后台服务";
            body = "点击打开控制台激活，或点下方「⏻ 停止并退出」关闭常驻";
            toggleLabel = "⇄ 切换键鼠";
        } else if (mirrorActive) {
            title = "ExtBridge 原生镜像模式 · 物理 Display #" + st.extId;
            body = "外接屏正在同步手机/平板主屏 | 点击右侧「切回DeX」恢复独立大屏桌面";
            toggleLabel = st.inputToExt == 1 ? "⇄ 切到本机" : "⇄ 切回外接";
        } else if (st.vdId <= 0) {
            title = "ExtBridge 待机中 · 等待 USB-C 外接屏";
            body = "预设 " + st.virtW + "×" + st.virtH + " (" + st.virtDpi + "dpi) | 不用时可点「⏻ 停止并退出」";
            toggleLabel = st.inputToExt == 1 ? "⇄ 切到平板" : "⇄ 切回扩展屏";
        } else {
            String target = st.inputToExt == 1 ? "外接扩展屏" : "平板本机";
            String modeTag = mUsingA11yOverlay ? "无障碍B" : "兼容A";
            title = "ExtBridge 已挂载(" + modeTag + ") · "
                    + st.virtW + "×" + st.virtH + " @" + Math.round(st.virtFps) + "Hz";
            body = "键鼠控制：【" + target + "】 | 点通知本体进控制台，右侧按钮一键回桌面";
            toggleLabel = st.inputToExt == 1 ? "⇄ 键鼠切回平板" : "⇄ 键鼠切回扩展屏";
        }

        Notification.Builder nb = new Notification.Builder(this, CH_ID)
                .setSmallIcon(R.drawable.ic_stat_extbridge)
                .setContentTitle(title)
                .setContentText(body)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(piMain);
        if (standbyOrDisconnected) {
            nb.addAction(new Notification.Action.Builder(null, "⊞ 打开控制台", piMain).build());
            nb.addAction(new Notification.Action.Builder(null, "⏻ 停止并退出", piExit).build());
        } else {
            String act1Label = mirrorActive ? "🖥 切回DeX" : toggleLabel;
            PendingIntent act1Pi = mirrorActive ? piMirror : piToggle;
            String act3Label = mirrorActive ? "⏻ 停止并退出" : "⊞ 回扩展桌面";
            PendingIntent act3Pi = mirrorActive ? piExit : piExtHome;
            nb.addAction(new Notification.Action.Builder(null, act1Label, act1Pi).build());
            nb.addAction(new Notification.Action.Builder(null, "🖐 大触摸板", piTp).build());
            nb.addAction(new Notification.Action.Builder(null, act3Label, act3Pi).build());
        }
        return nb.build();
    }

    private void notifyIfChanged(BridgeClient.Status st) {
        if (mNm == null || st == null) return;
        String sig = (st.connected ? 1 : 0) + ":" + st.mirrorMode + ":" + st.extId + ":" + st.vdId
                + ":" + st.virtW + "x" + st.virtH + "@" + st.virtDpi + ":" + Math.round(st.virtFps)
                + ":" + st.inputToExt + ":" + (mUsingA11yOverlay ? 1 : 0);
        if (!sig.equals(mLastNotifSig)) {
            mLastNotifSig = sig;
            mNm.notify(NOTIF_ID, buildNotification(st));
        }
    }

    private void pollRemoteStateAsync() {
        if (mDestroyed) return;
        final long seq = mPollSeq.incrementAndGet();
        safeBgExecute(() -> {
            BridgeClient.Status st = BridgeClient.getStatus();
            List<BridgeClient.TaskEntry> tasks = (st.connected && st.mirrorMode == 0 && st.vdId > 0)
                    ? BridgeClient.getTasks() : null;
            boolean needSys = st.connected && (st.vdId > 0 || (st.mirrorMode == 1 && st.extId > 0));
            BridgeClient.SysStatus sys = needSys ? BridgeClient.getSysStatus() : mLastSys;
            safePost(() -> {
                if (seq == mPollSeq.get()) {
                    applyStateOnUi(st, tasks, sys);
                }
            });
        });
    }

    private void applyStateOnUi(
            BridgeClient.Status st,
            List<BridgeClient.TaskEntry> tasks,
            BridgeClient.SysStatus sys) {
        if (mDestroyed) return;
        mLastStatus = st;
        if (sys != null && sys.valid) mLastSys = sys;
        notifyIfChanged(st);

        boolean a11yNow = NavAssistService.isRunning();
        if (st.connected && st.mirrorMode == 1) {
            if (mBlackoutActive) {
                setDexBlackout(false);
            }
            detachExtOverlays(false);
            return;
        }

        if (!st.connected || st.vdId <= 0) {
            detachExtOverlays(false);
            return;
        }
        if (!a11yNow && !Settings.canDrawOverlays(this)) return;

        int targetOverlayDpi = computeOverlayDpi(st);
        if (st.vdId != mAttachedVdId
                || a11yNow != mLastObservedA11y
                || targetOverlayDpi != mOverlayDpi) {
            attachExtOverlays(st);
        } else if (st.virtW != mOverlayW || st.virtH != mOverlayH) {
            mOverlayW = st.virtW;
            mOverlayH = st.virtH;
            mLastTaskSig = "";
            repositionDock();
            repositionPanel();
            repositionSplitDivider();
            repositionSplitMenu();
        }

        boolean launcherTop = (tasks != null);
        if (launcherTop) {
            for (BridgeClient.TaskEntry t : tasks) {
                if (t.isTopExt()) {
                    if (!"com.extbridge.helper".equals(t.pkg)) {
                        launcherTop = false;
                    }
                    break;
                }
            }
        }
        mIsOnLauncherHome = mInLauncherHost || launcherTop;

        updateInputToggleUi(st.inputToExt);
        if (tasks != null) updateTaskStrip(tasks);
        refreshLocalHardwareViews();
        refreshSysViews();
        if (mBtnPanelFullscreen != null) {
            boolean fsOn = st.fullscreenPolicy == 1;
            mBtnPanelFullscreen.setText(fsOn ? "⛶ 桌面横屏:开" : "⛶ 桌面横屏:关");
            mBtnPanelFullscreen.setTextColor(fsOn ? 0xFF7DD3FC : 0xFFCBD5E1);
        }
        if (mBtnPanelRotate != null) {
            mBtnPanelRotate.setText("↻ 旋转: " + BridgeClient.rotationLabel(st.virtRot));
            mBtnPanelRotate.setTextColor(st.virtRot != 0 ? 0xFF7DD3FC : 0xFFCBD5E1);
        }
        updatePanelModeChips(st.mirrorMode);
        updateDockAutoHideUi();
        updateBlackoutUi();
        updateSplitUi(st);
        evaluateDockAutoHide(false);
    }

    private void attachExtOverlays(BridgeClient.Status st) {
        detachExtOverlays(true);
        Display target = null;
        for (Display d : mDm.getDisplays()) {
            if (d.getDisplayId() == st.vdId) { target = d; break; }
        }
        if (target == null) return;

        NavAssistService a11y = NavAssistService.getInstance();
        mLastObservedA11y = (a11y != null);
        if (a11y != null) {
            try {
                attachWithContext(a11y.createDisplayContext(target),
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, true, st);
                mLastNotifSig = "";
                notifyIfChanged(st);
                return;
            } catch (Throwable t) {
                t.printStackTrace();
                detachExtOverlays(true);
                mLastObservedA11y = true;
            }
        }
        if (Settings.canDrawOverlays(this)) {
            try {
                attachWithContext(createDisplayContext(target),
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, false, st);
                mLastNotifSig = "";
                notifyIfChanged(st);
            } catch (Throwable t) {
                t.printStackTrace();
                detachExtOverlays(true);
            }
        }
    }

    private static int computeOverlayDpi(BridgeClient.Status st) {
        int reqDpi = (st != null && st.virtDpi > 0) ? st.virtDpi : 240;
        int virtW = (st != null && st.virtW > 0) ? st.virtW : 2560;
        int virtH = (st != null && st.virtH > 0) ? st.virtH : 1440;
        int minEdge = Math.min(virtW, virtH);
        int maxDpiForEdge = Math.max(80, Math.round(minEdge * 160f / 720f));
        return Math.max(80, Math.min(reqDpi, maxDpiForEdge));
    }

    private void attachWithContext(Context dispCtx, int winType, boolean isA11y, BridgeClient.Status st) {
        int overlayDpi = computeOverlayDpi(st);
        Context uiCtx = dispCtx;
        try {
            android.content.res.Configuration cfg = new android.content.res.Configuration(
                    dispCtx.getResources().getConfiguration());
            boolean needCfg = false;
            if (cfg.densityDpi != overlayDpi) {
                cfg.densityDpi = overlayDpi;
                needCfg = true;
            }
            if (cfg.fontScale != 1.0f) {
                cfg.fontScale = 1.0f;
                needCfg = true;
            }
            if (needCfg) {
                uiCtx = dispCtx.createConfigurationContext(cfg);
            }
        } catch (Throwable ignored) {}
        mExtCtx = uiCtx;
        mExtWm = (WindowManager) dispCtx.getSystemService(WINDOW_SERVICE);
        mWinType = winType;
        mUsingA11yOverlay = isA11y;
        mAttachedVdId = st.vdId;
        mOverlayW = st.virtW;
        mOverlayH = st.virtH;
        mOverlayDpi = overlayDpi;
        mLastTaskSig = "";

        buildSplitOverlays(mExtCtx, st);
        buildDockEdgeTriggerOverlay(mExtCtx);
        buildDockOverlay(mExtCtx, st);
        buildTaskBubbleOverlay(mExtCtx);
        buildWin11PanelOverlay(mExtCtx, st);
        buildCursorOverlay(mExtCtx, st); // Added LAST so cursor stays above Dock & Flyout
        syncOverlayHostSurface(true);
    }

    public static void setDockHidden(boolean hidden) {
        BridgeService svc = sInstance;
        if (svc == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            svc.applyDockHidden(hidden);
        } else if (svc.mHandler != null) {
            svc.mHandler.post(() -> svc.applyDockHidden(hidden));
        }
    }

    public static void setLauncherOverlayHost(ViewGroup host, int displayId) {
        sLauncherHost = host;
        sLauncherHostDisplayId = displayId;
        BridgeService svc = sInstance;
        if (svc == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            svc.syncOverlayHostSurface(false);
        } else if (svc.mHandler != null) {
            svc.mHandler.post(() -> svc.syncOverlayHostSurface(false));
        }
    }

    public static void clearLauncherOverlayHost(ViewGroup host) {
        if (sLauncherHost == host) {
            sLauncherHost = null;
            sLauncherHostDisplayId = -1;
            BridgeService svc = sInstance;
            if (svc == null) return;
            if (Looper.myLooper() == Looper.getMainLooper()) {
                svc.syncOverlayHostSurface(false);
            } else if (svc.mHandler != null) {
                svc.mHandler.post(() -> svc.syncOverlayHostSurface(false));
            }
        }
    }

    public static void dismissPanelOnHostTouch(float winX, float winY) {
        BridgeService svc = sInstance;
        if (svc != null) {
            svc.handleHostTouch(winX, winY);
        }
    }

    private boolean shouldHostInLauncher() {
        return !mUsingA11yOverlay
                && mAttachedVdId > 0
                && sLauncherHost != null
                && sLauncherHostDisplayId == mAttachedVdId
                && (mLastStatus == null || mLastStatus.splitActive == 0);
    }

    private void removeOverlayView(View v) {
        if (v == null) return;
        ViewParent p = v.getParent();
        if (p instanceof ViewGroup) {
            ((ViewGroup) p).removeView(v);
        } else if (p != null && mExtWm != null) {
            try { mExtWm.removeViewImmediate(v); } catch (Throwable ignored) {}
        }
    }

    private void syncOverlayHostSurface(boolean forceAttach) {
        if (mDockRoot == null || mPanelRoot == null || mCursorView == null || mExtCtx == null) return;
        boolean wantHost = shouldHostInLauncher();
        if (!forceAttach && wantHost == mInLauncherHost) {
            ViewParent p = mDockRoot.getParent();
            boolean rightParent = wantHost ? (p == sLauncherHost) : (p != null && !(p instanceof ViewGroup));
            if (rightParent) return;
        }
        removeOverlayView(mSplitDividerRoot);
        removeOverlayView(mDockEdgeTrigger);
        removeOverlayView(mDockRoot);
        removeOverlayView(mSplitMenuRoot);
        removeOverlayView(mTaskBubbleRoot);
        removeOverlayView(mPanelRoot);
        removeOverlayView(mCursorView);
        mInLauncherHost = wantHost;
        if (wantHost) {
            if (mSplitDividerRoot != null) {
                FrameLayout.LayoutParams sdlp = new FrameLayout.LayoutParams(
                        mSplitDividerLp != null ? mSplitDividerLp.width : dp(mExtCtx, 40),
                        mSplitDividerLp != null ? mSplitDividerLp.height : dp(mExtCtx, 84),
                        Gravity.TOP | Gravity.LEFT);
                sLauncherHost.addView(mSplitDividerRoot, sdlp);
            }
            if (mDockEdgeTrigger != null) {
                FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(
                        dp(mExtCtx, 220), dp(mExtCtx, 16), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                sLauncherHost.addView(mDockEdgeTrigger, elp);
            }
            FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                    mDockLp != null ? mDockLp.width : ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            dlp.bottomMargin = dp(mExtCtx, 10);
            sLauncherHost.addView(mDockRoot, dlp);

            if (mSplitMenuRoot != null) {
                FrameLayout.LayoutParams smlp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.LEFT);
                sLauncherHost.addView(mSplitMenuRoot, smlp);
            }

            if (mTaskBubbleRoot != null) {
                FrameLayout.LayoutParams tblp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.LEFT);
                sLauncherHost.addView(mTaskBubbleRoot, tblp);
            }

            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                    dp(mExtCtx, 354), ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.LEFT);
            if (mPanelLp != null) {
                plp.leftMargin = mPanelLp.x;
                plp.topMargin = mPanelLp.y;
            }
            sLauncherHost.addView(mPanelRoot, plp);

            mCursorView.setClickable(false);
            mCursorView.setFocusable(false);
            mCursorView.setElevation(dp(mExtCtx, 48));
            mCursorView.setTranslationZ(dp(mExtCtx, 48));
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                    44, 44, Gravity.TOP | Gravity.LEFT);
            sLauncherHost.addView(mCursorView, clp);
            mCursorView.bringToFront();
            if (mCursorLp != null) {
                mCursorView.setTranslationX(mCursorLp.x);
                mCursorView.setTranslationY(mCursorLp.y);
            }
            sLauncherHost.post(() -> {
                if (mInLauncherHost) {
                    repositionDock();
                    repositionSplitDivider();
                    repositionSplitMenu();
                    if (mTaskBubbleOpen) repositionTaskBubble();
                    if (mPanelOpen) repositionPanel();
                }
            });
        } else if (mExtWm != null) {
            mCursorView.setTranslationX(0f);
            mCursorView.setTranslationY(0f);
            if (mSplitDividerRoot != null && mSplitDividerLp != null) {
                try { mExtWm.addView(mSplitDividerRoot, mSplitDividerLp); } catch (Throwable ignored) {}
            }
            if (mDockEdgeTrigger != null && mDockEdgeLp != null) {
                try { mExtWm.addView(mDockEdgeTrigger, mDockEdgeLp); } catch (Throwable ignored) {}
            }
            try { mExtWm.addView(mDockRoot, mDockLp); } catch (Throwable ignored) {}
            if (mSplitMenuRoot != null && mSplitMenuLp != null) {
                try { mExtWm.addView(mSplitMenuRoot, mSplitMenuLp); } catch (Throwable ignored) {}
            }
            if (mTaskBubbleRoot != null && mTaskBubbleLp != null) {
                try { mExtWm.addView(mTaskBubbleRoot, mTaskBubbleLp); } catch (Throwable ignored) {}
            }
            try { mExtWm.addView(mPanelRoot, mPanelLp); } catch (Throwable ignored) {}
            try { mExtWm.addView(mCursorView, mCursorLp); } catch (Throwable ignored) {}
        }
        syncDockEdgeVisibility();
        repositionDock();
        repositionSplitDivider();
        repositionSplitMenu();
        if (mTaskBubbleOpen) repositionTaskBubble();
        if (mPanelOpen) repositionPanel();
        evaluateDockAutoHide(false);
    }

    private void handleHostTouch(float winX, float winY) {
        if (!mInLauncherHost) return;
        if (mPanelOpen && mPanelCard != null
                && !isPointInView(mPanelCard, winX, winY) && !isPointInView(mTrayBtn, winX, winY)) {
            setPanelOpen(false);
        }
        if (mTaskBubbleOpen && mTaskBubbleCard != null
                && !isPointInView(mTaskBubbleCard, winX, winY)) {
            setTaskBubbleOpen(false);
        }
    }

    private static boolean isPointInView(View v, float winX, float winY) {
        if (v == null || v.getVisibility() != View.VISIBLE || v.getWidth() <= 0 || v.getHeight() <= 0) {
            return false;
        }
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        return winX >= loc[0] && winX <= (loc[0] + v.getWidth())
                && winY >= loc[1] && winY <= (loc[1] + v.getHeight());
    }

    private void buildDockOverlay(Context ctx, BridgeClient.Status st) {
        mDockRoot = new LinearLayout(ctx);
        mDockRoot.setOrientation(LinearLayout.HORIZONTAL);
        mDockRoot.setGravity(Gravity.CENTER_VERTICAL);
        mDockRoot.setBaselineAligned(false);
        mDockRoot.setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5));
        mDockRoot.setElevation(dp(ctx, 10));
        GradientDrawable dockBg = new GradientDrawable();
        dockBg.setColor(0xD2121926);
        dockBg.setCornerRadius(dp(ctx, 22));
        dockBg.setStroke(1, 0x42FFFFFF);
        mDockRoot.setBackground(dockBg);
        mDockRoot.setOnHoverListener((v, ev) -> {
            int act = ev.getActionMasked();
            if (act == MotionEvent.ACTION_HOVER_ENTER || act == MotionEvent.ACTION_HOVER_MOVE) {
                mDockHovered = true;
                cancelAutoHideTimer();
            } else if (act == MotionEvent.ACTION_HOVER_EXIT) {
                mDockHovered = false;
                evaluateDockAutoHide(false);
            }
            return false;
        });

        // Left: App Launcher/Search, Back, Split, Input Switch
        TextView btnHome = makeDockBtn(ctx, "⊞ 桌面", 0x33FFFFFF, Color.WHITE);
        btnHome.setOnClickListener(v -> {
            setPanelOpen(false);
            setSplitMenuOpen(false);
            BridgeClient.relaunchLauncher();
        });
        mDockRoot.addView(btnHome);

        TextView btnBack = makeDockBtn(ctx, "←", 0x26FFFFFF, Color.WHITE);
        btnBack.setOnClickListener(v -> BridgeClient.sendBack());
        mDockRoot.addView(btnBack);

        mBtnDockSplit = makeDockBtn(ctx, "◫ 分屏", 0x26FFFFFF, Color.WHITE);
        mBtnDockSplit.setOnClickListener(v -> triggerQuickSplitOrMenu());
        mBtnDockSplit.setOnLongClickListener(v -> {
            setSplitMenuOpen(!mSplitMenuOpen);
            return true;
        });
        mDockRoot.addView(mBtnDockSplit);

        mBtnToggle = makeDockBtn(ctx, st.inputToExt == 1 ? "⇄ 外接" : "⇄ 平板",
                st.inputToExt == 1 ? 0xCC2563EB : 0xCCEA580C, Color.WHITE);
        mBtnToggle.setOnClickListener(v -> mBg.execute(() -> {
            int m = BridgeClient.toggleInput();
            mHandler.post(() -> {
                updateInputToggleUi(m);
                Toast.makeText(BridgeService.this, m == 1 ? "键鼠 -> 扩展屏" : "键鼠 -> 平板", Toast.LENGTH_SHORT).show();
            });
        }));
        mDockRoot.addView(mBtnToggle);

        mTaskDivider = makeVerticalDivider(ctx);
        mTaskDivider.setVisibility(View.GONE);
        mDockRoot.addView(mTaskDivider);

        // Center: Multitasking running/recent apps strip
        mTaskStrip = new LinearLayout(ctx);
        mTaskStrip.setOrientation(LinearLayout.HORIZONTAL);
        mTaskStrip.setGravity(Gravity.CENTER_VERTICAL);
        mDockRoot.addView(mTaskStrip);

        mDockRoot.addView(makeVerticalDivider(ctx));

        // Right: Win11 System Tray Pill (Wi-Fi, Vol, Battery %, Clock + Date)
        mTrayBtn = new LinearLayout(ctx);
        mTrayBtn.setOrientation(LinearLayout.HORIZONTAL);
        mTrayBtn.setGravity(Gravity.CENTER_VERTICAL);
        mTrayBtn.setPadding(dp(ctx, 9), dp(ctx, 4), dp(ctx, 9), dp(ctx, 4));
        updateTrayPillBackground(false);
        mTrayBtn.setOnTouchListener((v, ev) -> {
            int act = ev.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) {
                mTrayPressed = true;
                mHandler.removeCallbacks(mOutsideDismissTask);
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                mTrayPressed = false;
                mLastTrayUpMs = SystemClock.uptimeMillis();
            }
            return false;
        });
        mTrayBtn.setOnClickListener(v -> {
            mHandler.removeCallbacks(mOutsideDismissTask);
            mLastTrayToggleMs = SystemClock.uptimeMillis();
            setPanelOpen(!mPanelOpen);
        });

        mTrayWifiIcon = new MiniIconView(ctx, MiniIconView.KIND_WIFI, Color.WHITE);
        LinearLayout.LayoutParams wiLp = new LinearLayout.LayoutParams(dp(ctx, 16), dp(ctx, 16));
        wiLp.rightMargin = dp(ctx, 6);
        mTrayBtn.addView(mTrayWifiIcon, wiLp);

        mTrayVolIcon = new MiniIconView(ctx, MiniIconView.KIND_VOLUME, Color.WHITE);
        LinearLayout.LayoutParams voLp = new LinearLayout.LayoutParams(dp(ctx, 16), dp(ctx, 16));
        voLp.rightMargin = dp(ctx, 6);
        mTrayBtn.addView(mTrayVolIcon, voLp);

        mTrayBattView = new MiniBatteryView(ctx);
        LinearLayout.LayoutParams bvLp = new LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 12));
        bvLp.rightMargin = dp(ctx, 4);
        mTrayBtn.addView(mTrayBattView, bvLp);

        mTrayBattPct = new TextView(ctx);
        mTrayBattPct.setTextColor(Color.WHITE);
        mTrayBattPct.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f);
        mTrayBattPct.getPaint().setFakeBoldText(true);
        enforceSingleLine(mTrayBattPct);
        LinearLayout.LayoutParams bpLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bpLp.rightMargin = dp(ctx, 9);
        mTrayBtn.addView(mTrayBattPct, bpLp);

        LinearLayout clockCol = new LinearLayout(ctx);
        clockCol.setOrientation(LinearLayout.VERTICAL);
        clockCol.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

        TextClock tcTime = new TextClock(ctx);
        tcTime.setFormat24Hour("HH:mm");
        tcTime.setFormat12Hour("HH:mm");
        tcTime.setTextColor(Color.WHITE);
        tcTime.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        tcTime.getPaint().setFakeBoldText(true);
        enforceSingleLine(tcTime);
        clockCol.addView(tcTime);

        TextClock tcDate = new TextClock(ctx);
        tcDate.setFormat24Hour("yyyy/M/d");
        tcDate.setFormat12Hour("yyyy/M/d");
        tcDate.setTextColor(0xFFCBD5E1);
        tcDate.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9.5f);
        enforceSingleLine(tcDate);
        clockCol.addView(tcDate);

        mTrayBtn.addView(clockCol);
        mDockRoot.addView(mTrayBtn);

        mDockLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                mWinType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        mDockLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        mDockLp.x = 0;
        mDockLp.y = dp(ctx, 10);

        mDockRoot.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if ((r - l) != (or - ol) || (b - t) != (ob - ot)) {
                repositionDock();
                if (mPanelOpen) repositionPanel();
            }
        });
    }

    private void buildWin11PanelOverlay(Context ctx, BridgeClient.Status st) {
        mPanelRoot = new FrameLayout(ctx);
        mPanelRoot.setClipToPadding(false);
        mPanelRoot.setClipChildren(false);
        mPanelRoot.setPadding(dp(ctx, 8), dp(ctx, 14), dp(ctx, 8), dp(ctx, 8));

        mPanelCard = new LinearLayout(ctx);
        mPanelCard.setOrientation(LinearLayout.VERTICAL);
        mPanelCard.setElevation(dp(ctx, 16));
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(0xEE18202E);
        panelBg.setCornerRadius(dp(ctx, 16));
        panelBg.setStroke(1, 0x3FFFFFFF);
        mPanelCard.setBackground(panelBg);

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 14));

        // Row 1 of 3x2 grid: Wi-Fi, Bluetooth, Input Target
        LinearLayout row1 = new LinearLayout(ctx);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(ctx, 10);

        mTileWifi = new QuickTile(ctx, MiniIconView.KIND_WIFI, "Wi-Fi", true,
                () -> execSysAndRefresh(BridgeClient.SYS_WIFI, -1, null),
                () -> openExtSettings(Settings.ACTION_WIFI_SETTINGS));
        mTileBt = new QuickTile(ctx, MiniIconView.KIND_BT, "蓝牙", true,
                () -> execSysAndRefresh(BridgeClient.SYS_BT, -1, null),
                () -> openExtSettings(Settings.ACTION_BLUETOOTH_SETTINGS));
        mTileInput = new QuickTile(ctx, MiniIconView.KIND_DISPLAY_SWITCH, "外接控制", false,
                () -> mBg.execute(() -> {
                    int m = BridgeClient.toggleInput();
                    mHandler.post(() -> {
                        updateInputToggleUi(m);
                        pollRemoteStateAsync();
                    });
                }), null);
        addGridTile(row1, mTileWifi.root, true);
        addGridTile(row1, mTileBt.root, true);
        addGridTile(row1, mTileInput.root, false);
        body.addView(row1, rowLp);

        // Row 2 of 3x2 grid: Airplane, Touchpad, Battery Saver
        LinearLayout row2 = new LinearLayout(ctx);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        row2Lp.bottomMargin = dp(ctx, 16);

        mTileAirplane = new QuickTile(ctx, MiniIconView.KIND_AIRPLANE, "飞行模式", false,
                () -> execSysAndRefresh(BridgeClient.SYS_AIRPLANE, -1, null), null);
        mTileTouchpad = new QuickTile(ctx, MiniIconView.KIND_TOUCHPAD, "大触摸板", true,
                this::openTabletTouchpad, this::openTabletTouchpad);
        mTilePowerSave = new QuickTile(ctx, MiniIconView.KIND_BATTERY_SAVER, "节能模式", false,
                () -> execSysAndRefresh(BridgeClient.SYS_POWER_SAVE, -1, null), null);
        addGridTile(row2, mTileAirplane.root, true);
        addGridTile(row2, mTileTouchpad.root, true);
        addGridTile(row2, mTilePowerSave.root, false);
        body.addView(row2, row2Lp);

        // Volume Slider row (no brightness slider, per user preference)
        LinearLayout volRow = new LinearLayout(ctx);
        volRow.setOrientation(LinearLayout.HORIZONTAL);
        volRow.setGravity(Gravity.CENTER_VERTICAL);
        volRow.setPadding(dp(ctx, 2), dp(ctx, 4), dp(ctx, 2), dp(ctx, 2));

        mVolSpeakerIcon = new MiniIconView(ctx, MiniIconView.KIND_VOLUME, Color.WHITE);
        mVolSpeakerIcon.setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6));
        mVolSpeakerIcon.setOnClickListener(v -> {
            if (mAm != null) {
                try {
                    mAm.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0);
                } catch (Throwable ignored) {}
                refreshLocalHardwareViews();
            }
        });
        volRow.addView(mVolSpeakerIcon, new LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 28)));

        mVolSeekBar = new SeekBar(ctx);
        int accent = 0xFFA6D8E8;
        if (mVolSeekBar.getProgressDrawable() != null) {
            mVolSeekBar.getProgressDrawable().setColorFilter(new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN));
        }
        if (mVolSeekBar.getThumb() != null) {
            mVolSeekBar.getThumb().setColorFilter(new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN));
        }
        if (mAm != null) {
            mVolSeekBar.setMax(Math.max(1, mAm.getStreamMaxVolume(AudioManager.STREAM_MUSIC)));
        }
        mVolSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && mAm != null) {
                    try {
                        mAm.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
                    } catch (Throwable ignored) {}
                    refreshTrayIconsOnly();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { mSeekingVol = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) { mSeekingVol = false; refreshLocalHardwareViews(); }
        });
        LinearLayout.LayoutParams skLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        skLp.leftMargin = dp(ctx, 4);
        skLp.rightMargin = dp(ctx, 4);
        volRow.addView(mVolSeekBar, skLp);

        TextView btnAudioMore = new TextView(ctx);
        btnAudioMore.setText("🎚 ›");
        btnAudioMore.setTextColor(0xFFCBD5E1);
        btnAudioMore.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        btnAudioMore.setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 6), dp(ctx, 4));
        btnAudioMore.setOnClickListener(v -> openExtSettings(Settings.ACTION_SOUND_SETTINGS));
        volRow.addView(btnAudioMore);

        body.addView(volRow);

        // Display output mode selector (DeX / Mirror)
        LinearLayout modeRow = new LinearLayout(ctx);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams mrLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mrLp.topMargin = dp(ctx, 10);
        modeRow.setLayoutParams(mrLp);

        mBtnPanelDex = makeFooterChip(ctx, "🖥 DeX桌面");
        mBtnPanelDex.setSingleLine(true);
        mBtnPanelDex.setGravity(Gravity.CENTER);
        mBtnPanelDex.setOnClickListener(v -> switchPanelMode(BridgeClient.MIRROR_MODE_DEX));
        LinearLayout.LayoutParams mp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        mp1.rightMargin = dp(ctx, 6);
        modeRow.addView(mBtnPanelDex, mp1);

        mBtnPanelMirror = makeFooterChip(ctx, "📺 原生镜像");
        mBtnPanelMirror.setSingleLine(true);
        mBtnPanelMirror.setGravity(Gravity.CENTER);
        mBtnPanelMirror.setOnClickListener(v -> switchPanelMode(BridgeClient.MIRROR_MODE_MIRROR));
        LinearLayout.LayoutParams mp3 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        modeRow.addView(mBtnPanelMirror, mp3);

        updatePanelModeChips(st.mirrorMode);
        body.addView(modeRow);

        // DeX policy & action strips
        LinearLayout dexRow = new LinearLayout(ctx);
        dexRow.setOrientation(LinearLayout.HORIZONTAL);
        dexRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams drLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        drLp.topMargin = dp(ctx, 6);
        dexRow.setLayoutParams(drLp);

        mBtnPanelFullscreen = makeFooterChip(ctx, st.fullscreenPolicy == 1 ? "⛶ 桌面横屏:开" : "⛶ 桌面横屏:关");
        mBtnPanelFullscreen.setSingleLine(true);
        mBtnPanelFullscreen.setGravity(Gravity.CENTER);
        mBtnPanelFullscreen.setTextColor(st.fullscreenPolicy == 1 ? 0xFF7DD3FC : 0xFFCBD5E1);
        mBtnPanelFullscreen.setOnClickListener(v -> mBg.execute(() -> {
            int next = BridgeClient.toggleFullscreenPolicy();
            mHandler.post(() -> {
                Toast.makeText(BridgeService.this,
                        next == 1 ? "已开启：DeX 默认横屏大屏桌面（单应用可在桌面长按改模式）" : "已关闭：DeX 桌面默认横屏锁定",
                        Toast.LENGTH_SHORT).show();
                pollRemoteStateAsync();
            });
        }));
        LinearLayout.LayoutParams dp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        dp1.rightMargin = dp(ctx, 6);
        dexRow.addView(mBtnPanelFullscreen, dp1);

        mBtnPanelRotate = makeFooterChip(ctx, "↻ 旋转: " + BridgeClient.rotationLabel(st.virtRot));
        mBtnPanelRotate.setSingleLine(true);
        mBtnPanelRotate.setGravity(Gravity.CENTER);
        mBtnPanelRotate.setTextColor(st.virtRot != 0 ? 0xFF7DD3FC : 0xFFCBD5E1);
        mBtnPanelRotate.setOnClickListener(v -> mBg.execute(() -> {
            int next = BridgeClient.cycleRotation();
            mHandler.post(() -> {
                Toast.makeText(BridgeService.this,
                        "输出旋转已切换为：" + BridgeClient.rotationLabel(next),
                        Toast.LENGTH_SHORT).show();
                pollRemoteStateAsync();
            });
        }));
        LinearLayout.LayoutParams dp3a = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        dexRow.addView(mBtnPanelRotate, dp3a);

        body.addView(dexRow);

        LinearLayout dexRow2 = new LinearLayout(ctx);
        dexRow2.setOrientation(LinearLayout.HORIZONTAL);
        dexRow2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams dr2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dr2Lp.topMargin = dp(ctx, 6);
        dexRow2.setLayoutParams(dr2Lp);

        mBtnPanelSplit = makeFooterChip(ctx, st.splitActive == 1 ? "◫ 退出分屏" : "◫ 一键分屏");
        mBtnPanelSplit.setSingleLine(true);
        mBtnPanelSplit.setGravity(Gravity.CENTER);
        mBtnPanelSplit.setTextColor(st.splitActive == 1 ? 0xFF7DD3FC : 0xFFCBD5E1);
        mBtnPanelSplit.setOnClickListener(v -> {
            setPanelOpen(false);
            if (mLastStatus.splitActive == 1) {
                mBg.execute(() -> {
                    BridgeClient.exitSplitMode();
                    mHandler.post(() -> {
                        Toast.makeText(BridgeService.this, "已退出分屏模式", Toast.LENGTH_SHORT).show();
                        pollRemoteStateAsync();
                    });
                });
            } else {
                triggerQuickSplitOrMenu();
            }
        });
        LinearLayout.LayoutParams dp3s = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        dp3s.rightMargin = dp(ctx, 6);
        dexRow2.addView(mBtnPanelSplit, dp3s);

        mBtnPanelHideDock = makeFooterChip(ctx, mDockAutoHide ? "📌 Dock自动隐藏" : "📌 Dock固定显示");
        mBtnPanelHideDock.setSingleLine(true);
        mBtnPanelHideDock.setGravity(Gravity.CENTER);
        mBtnPanelHideDock.setTextColor(mDockAutoHide ? 0xFF7DD3FC : 0xFFCBD5E1);
        mBtnPanelHideDock.setOnClickListener(v -> {
            boolean next = !mDockAutoHide;
            setDockAutoHideEnabled(BridgeService.this, next);
            Toast.makeText(BridgeService.this,
                    next ? "已开启 Dock 自动隐藏（应用内闲置自动收起，鼠标移至底边唤醒）"
                         : "已关闭自动隐藏：Dock 栏将始终固定显示",
                    Toast.LENGTH_SHORT).show();
        });
        mBtnPanelHideDock.setOnLongClickListener(v -> {
            applyDockHidden(true);
            Toast.makeText(BridgeService.this, "Dock 已暂时收起，触碰底边触发条可唤醒", Toast.LENGTH_SHORT).show();
            return true;
        });
        LinearLayout.LayoutParams dp3b = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        dexRow2.addView(mBtnPanelHideDock, dp3b);

        body.addView(dexRow2);

        LinearLayout dexRow3 = new LinearLayout(ctx);
        dexRow3.setOrientation(LinearLayout.HORIZONTAL);
        dexRow3.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams dr3Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dr3Lp.topMargin = dp(ctx, 6);
        dexRow3.setLayoutParams(dr3Lp);

        mBtnPanelBlackout = makeFooterChip(ctx, mBlackoutActive ? "🌙 已黑屏 · 点此恢复平板亮屏" : "🌙 一键黑屏 (平板最低亮度)");
        mBtnPanelBlackout.setSingleLine(true);
        mBtnPanelBlackout.setGravity(Gravity.CENTER);
        mBtnPanelBlackout.setOnClickListener(v -> toggleDexBlackout());
        dexRow3.addView(mBtnPanelBlackout, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        updateBlackoutUi();
        body.addView(dexRow3);

        mPanelCard.addView(body);

        // Footer Bar (Battery % left, Control Center + Settings right)
        LinearLayout footer = new LinearLayout(ctx);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10));
        GradientDrawable footBg = new GradientDrawable();
        footBg.setColor(0x440B111C);
        float r = dp(ctx, 16);
        footBg.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
        footer.setBackground(footBg);

        mPanelBattView = new MiniBatteryView(ctx);
        LinearLayout.LayoutParams pbvLp = new LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 13));
        pbvLp.rightMargin = dp(ctx, 7);
        footer.addView(mPanelBattView, pbvLp);

        mPanelBattText = new TextView(ctx);
        mPanelBattText.setTextColor(0xFFE2E8F0);
        mPanelBattText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        footer.addView(mPanelBattText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnCtrl = makeFooterChip(ctx, "🎛 控制台");
        btnCtrl.setOnClickListener(v -> openTabletMain());
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cLp.rightMargin = dp(ctx, 8);
        footer.addView(btnCtrl, cLp);

        TextView btnSysSet = makeFooterChip(ctx, "⚙ 设置");
        btnSysSet.setOnClickListener(v -> openExtSettings(Settings.ACTION_SETTINGS));
        footer.addView(btnSysSet);

        mPanelCard.addView(footer);
        mPanelRoot.addView(mPanelCard, new FrameLayout.LayoutParams(
                dp(ctx, 338), FrameLayout.LayoutParams.WRAP_CONTENT));
        mPanelRoot.setVisibility(View.GONE);
        mPanelOpen = false;

        mPanelLp = new WindowManager.LayoutParams(
                dp(ctx, 354),
                WindowManager.LayoutParams.WRAP_CONTENT,
                mWinType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        mPanelLp.gravity = Gravity.TOP | Gravity.LEFT;
        mPanelLp.x = Math.max(16, st.virtW - dp(ctx, 366));
        mPanelLp.y = Math.max(16, st.virtH - dp(ctx, 410));

        mPanelRoot.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE && mPanelOpen) {
                long now = SystemClock.uptimeMillis();
                if (!mTrayPressed && (now - mLastTrayUpMs) > 220L && (now - mLastTrayToggleMs) > 220L) {
                    mHandler.removeCallbacks(mOutsideDismissTask);
                    mHandler.postDelayed(mOutsideDismissTask, 70L);
                }
                return false;
            }
            return false;
        });
        mPanelRoot.addOnLayoutChangeListener((v, l, t, rt, b, ol, ot, or, ob) -> {
            if ((rt - l) != (or - ol) || (b - t) != (ob - ot)) {
                repositionPanel();
            }
        });
    }

    private void buildDockEdgeTriggerOverlay(Context ctx) {
        mDockEdgeTrigger = new FrameLayout(ctx);
        mDockEdgeTrigger.setClickable(true);
        mDockEdgeTrigger.setFocusable(false);

        mDockEdgePill = new View(ctx);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(0x88FFFFFF);
        pillBg.setCornerRadius(dp(ctx, 2.5f));
        pillBg.setStroke(1, 0x44000000);
        mDockEdgePill.setBackground(pillBg);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                dp(ctx, 108), dp(ctx, 4), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        plp.bottomMargin = dp(ctx, 4);
        mDockEdgeTrigger.addView(mDockEdgePill, plp);

        mDockEdgeTrigger.setOnHoverListener((v, ev) -> {
            int act = ev.getActionMasked();
            if (act == MotionEvent.ACTION_HOVER_ENTER || act == MotionEvent.ACTION_HOVER_MOVE) {
                wakeDockFromEdge();
            }
            return false;
        });
        mDockEdgeTrigger.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                wakeDockFromEdge();
                return true;
            }
            return false;
        });

        mDockEdgeLp = new WindowManager.LayoutParams(
                dp(ctx, 220),
                dp(ctx, 16),
                mWinType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        mDockEdgeLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        mDockEdgeLp.x = 0;
        mDockEdgeLp.y = 0;
        mDockEdgeTrigger.setVisibility(View.GONE);
    }

    private void buildSplitOverlays(Context ctx, BridgeClient.Status st) {
        mSplitDividerPortrait = (mOverlayH > mOverlayW);
        mSplitDividerRoot = new FrameLayout(ctx);
        mSplitDividerRoot.setVisibility(View.GONE);

        mSplitDividerPill = new TextView(ctx);
        mSplitDividerPill.setTextColor(0xFFF8FAFC);
        mSplitDividerPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        mSplitDividerPill.getPaint().setFakeBoldText(true);
        mSplitDividerPill.setGravity(Gravity.CENTER);
        mSplitDividerPill.setElevation(dp(ctx, 12));
        GradientDrawable divBg = new GradientDrawable();
        divBg.setColor(0xE00F172A);
        divBg.setCornerRadius(dp(ctx, 12));
        divBg.setStroke(dp(ctx, 1), 0x8838BDF8);
        mSplitDividerPill.setBackground(divBg);
        mSplitDividerRoot.addView(mSplitDividerPill, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        final float[] dragState = new float[5]; // [downRawX, downRawY, startRatio, curRatio, moved]
        final int touchSlop = dp(ctx, 6);
        mSplitDividerRoot.setOnTouchListener((v, ev) -> {
            int act = ev.getActionMasked();
            boolean port = mOverlayH > mOverlayW;
            if (act == MotionEvent.ACTION_DOWN) {
                mSplitDragging = true;
                dragState[0] = ev.getRawX();
                dragState[1] = ev.getRawY();
                int rPct = mLastStatus != null && mLastStatus.splitRatioPct > 0 ? mLastStatus.splitRatioPct : 50;
                dragState[2] = Math.max(0.25f, Math.min(0.75f, rPct / 100f));
                dragState[3] = dragState[2];
                dragState[4] = 0f;
                return true;
            } else if (act == MotionEvent.ACTION_MOVE) {
                float dx = ev.getRawX() - dragState[0];
                float dy = ev.getRawY() - dragState[1];
                if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                    dragState[4] = 1f;
                }
                int axis = Math.max(600, port ? mOverlayH : mOverlayW);
                float deltaRatio = (port ? dy : dx) / (float) axis;
                float r = Math.max(0.25f, Math.min(0.75f, dragState[2] + deltaRatio));
                dragState[3] = r;
                positionSplitDividerAt(r, true);
                return true;
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                mSplitDragging = false;
                if (dragState[4] < 0.5f && act == MotionEvent.ACTION_UP) {
                    setSplitMenuOpen(!mSplitMenuOpen);
                } else {
                    int pct = Math.max(25, Math.min(75, Math.round(dragState[3] * 100f)));
                    mLastStatus.splitRatioPct = pct;
                    positionSplitDividerAt(pct / 100f, false);
                    mBg.execute(() -> {
                        BridgeClient.setSplitRatio(pct);
                        mHandler.post(() -> {
                            Toast.makeText(BridgeService.this,
                                    "分屏比例：" + pct + "% : " + (100 - pct) + "%", Toast.LENGTH_SHORT).show();
                            pollRemoteStateAsync();
                        });
                    });
                }
                return true;
            }
            return false;
        });

        mSplitDividerLp = new WindowManager.LayoutParams(
                mSplitDividerPortrait ? dp(ctx, 76) : dp(ctx, 24),
                mSplitDividerPortrait ? dp(ctx, 24) : dp(ctx, 76),
                mWinType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        mSplitDividerLp.gravity = Gravity.TOP | Gravity.LEFT;
        mSplitDividerLp.x = Math.max(0, mOverlayW / 2 - dp(ctx, 12));
        mSplitDividerLp.y = Math.max(0, mOverlayH / 2 - dp(ctx, 38));

        // Compact Split Control Popover Menu
        mSplitMenuRoot = new LinearLayout(ctx);
        mSplitMenuRoot.setOrientation(LinearLayout.VERTICAL);
        mSplitMenuRoot.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        mSplitMenuRoot.setElevation(dp(ctx, 16));
        mSplitMenuRoot.setVisibility(View.GONE);
        GradientDrawable menuBg = new GradientDrawable();
        menuBg.setColor(0xEB0F172A);
        menuBg.setCornerRadius(dp(ctx, 16));
        menuBg.setStroke(1, 0x5538BDF8);
        mSplitMenuRoot.setBackground(menuBg);

        TextView title = new TextView(ctx);
        title.setText("◫ 分屏比例与控制");
        title.setTextColor(0xFFE2E8F0);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        title.getPaint().setFakeBoldText(true);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tLp.bottomMargin = dp(ctx, 8);
        mSplitMenuRoot.addView(title, tLp);

        LinearLayout ratiosRow = new LinearLayout(ctx);
        ratiosRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < SPLIT_PRESET_PCTS.length; i++) {
            final int pct = SPLIT_PRESET_PCTS[i];
            String lbl = (pct / 10) + ":" + ((100 - pct) / 10);
            TextView btn = makeFooterChip(ctx, lbl);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            btn.setOnClickListener(v -> {
                setSplitMenuOpen(false);
                mLastStatus.splitRatioPct = pct;
                positionSplitDividerAt(pct / 100f, false);
                mBg.execute(() -> {
                    BridgeClient.setSplitRatio(pct);
                    mHandler.post(() -> {
                        Toast.makeText(BridgeService.this,
                                "分屏比例：" + pct + "% : " + (100 - pct) + "%", Toast.LENGTH_SHORT).show();
                        pollRemoteStateAsync();
                    });
                });
            });
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i < SPLIT_PRESET_PCTS.length - 1) bLp.rightMargin = dp(ctx, 5);
            ratiosRow.addView(btn, bLp);
            mSplitRatioBtns[i] = btn;
        }
        mSplitMenuRoot.addView(ratiosRow);

        LinearLayout actRow = new LinearLayout(ctx);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        aLp.topMargin = dp(ctx, 8);

        mSplitSwapBtn = makeFooterChip(ctx, mSplitDividerPortrait ? "⇅ 上下互换" : "⇄ 左右互换");
        mSplitSwapBtn.setGravity(Gravity.CENTER);
        mSplitSwapBtn.setOnClickListener(v -> {
            setSplitMenuOpen(false);
            mBg.execute(() -> {
                BridgeClient.swapSplitSlots();
                mHandler.post(this::pollRemoteStateAsync);
            });
        });
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        swLp.rightMargin = dp(ctx, 6);
        actRow.addView(mSplitSwapBtn, swLp);

        TextView exitBtn = makeFooterChip(ctx, "✕ 退出分屏");
        exitBtn.setGravity(Gravity.CENTER);
        exitBtn.setTextColor(0xFFFCA5A5);
        exitBtn.setOnClickListener(v -> {
            setSplitMenuOpen(false);
            mBg.execute(() -> {
                BridgeClient.exitSplitMode();
                mHandler.post(() -> {
                    Toast.makeText(BridgeService.this, "已退出分屏", Toast.LENGTH_SHORT).show();
                    pollRemoteStateAsync();
                });
            });
        });
        actRow.addView(exitBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        mSplitMenuRoot.addView(actRow, aLp);

        mSplitMenuLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                mWinType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        mSplitMenuLp.gravity = Gravity.TOP | Gravity.LEFT;
        mSplitMenuRoot.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE && mSplitMenuOpen) {
                setSplitMenuOpen(false);
            }
            return false;
        });
    }

    private void cancelAutoHideTimer() {
        mHandler.removeCallbacks(mAutoHideDockTask);
        mAutoHideScheduled = false;
    }

    private void applyDockHidden(boolean hidden) {
        cancelAutoHideTimer();
        mDockHidden = hidden;
        if (hidden) {
            setPanelOpen(false);
            setSplitMenuOpen(false);
            setTaskBubbleOpen(false);
        }
        if (mDockRoot != null) {
            mDockRoot.animate().cancel();
            mDockRoot.setAlpha(1f);
            mDockRoot.setTranslationY(0f);
            mDockRoot.setVisibility(hidden ? View.GONE : View.VISIBLE);
            if (!hidden) repositionDock();
        }
        syncDockEdgeVisibility();
        bringCursorToFront();
    }

    private void applyDockHiddenAnimated(boolean hidden) {
        if (mDockRoot == null || mExtCtx == null) {
            applyDockHidden(hidden);
            return;
        }
        if (hidden == mDockHidden && ((hidden && mDockRoot.getVisibility() != View.VISIBLE)
                || (!hidden && mDockRoot.getVisibility() == View.VISIBLE))) {
            syncDockEdgeVisibility();
            return;
        }
        cancelAutoHideTimer();
        mDockHidden = hidden;
        mDockRoot.animate().cancel();
        if (hidden) {
            setPanelOpen(false);
            setSplitMenuOpen(false);
            setTaskBubbleOpen(false);
            syncDockEdgeVisibility();
            float slideDist = dp(mExtCtx, 74);
            mDockRoot.animate()
                    .translationY(slideDist)
                    .alpha(0f)
                    .setDuration(200L)
                    .setInterpolator(new AccelerateInterpolator(1.3f))
                    .withEndAction(() -> {
                        if (mDockHidden && mDockRoot != null) {
                            mDockRoot.setVisibility(View.GONE);
                            mDockRoot.setTranslationY(0f);
                            mDockRoot.setAlpha(1f);
                        }
                        bringCursorToFront();
                    })
                    .start();
        } else {
            syncDockEdgeVisibility();
            mDockRoot.setVisibility(View.VISIBLE);
            repositionDock();
            mDockRoot.setTranslationY(dp(mExtCtx, 64));
            mDockRoot.setAlpha(0f);
            mDockRoot.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(200L)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .withEndAction(this::bringCursorToFront)
                    .start();
        }
    }

    private void syncDockEdgeVisibility() {
        if (mDockEdgeTrigger != null) {
            mDockEdgeTrigger.setVisibility(mDockHidden ? View.VISIBLE : View.GONE);
        }
    }

    private void wakeDockFromEdge() {
        if (mDockHidden) {
            applyDockHiddenAnimated(false);
        }
        evaluateDockAutoHide(true);
    }

    private void evaluateDockAutoHide(boolean forceDelay) {
        if (!mDockAutoHide || mIsOnLauncherHome) {
            mHandler.removeCallbacks(mAutoHideDockTask);
            mAutoHideScheduled = false;
            if (mDockHidden) applyDockHiddenAnimated(false);
            return;
        }
        if (mPanelOpen || mSplitMenuOpen || mTaskBubbleOpen || mDockHovered || mTrayPressed) {
            mHandler.removeCallbacks(mAutoHideDockTask);
            mAutoHideScheduled = false;
            return;
        }
        if (mDockHidden && !forceDelay) return;
        if (!forceDelay && mAutoHideScheduled) return;
        mHandler.removeCallbacks(mAutoHideDockTask);
        mAutoHideScheduled = true;
        mHandler.postDelayed(mAutoHideDockTask, DOCK_AUTO_HIDE_DELAY_MS);
    }

    private void switchPanelMode(int mode) {
        setPanelOpen(false);
        Toast.makeText(BridgeService.this,
                "正在切换为【" + BridgeClient.mirrorModeLabel(mode) + "】...",
                Toast.LENGTH_SHORT).show();
        mBg.execute(() -> {
            BridgeClient.setMirrorMode(mode);
            mHandler.post(this::pollRemoteStateAsync);
        });
    }

    private void updatePanelModeChips(int mirrorMode) {
        if (mBtnPanelDex != null) {
            mBtnPanelDex.setTextColor(mirrorMode == BridgeClient.MIRROR_MODE_DEX ? 0xFF7DD3FC : 0xFFCBD5E1);
        }
        if (mBtnPanelMirror != null) {
            mBtnPanelMirror.setTextColor(mirrorMode == BridgeClient.MIRROR_MODE_MIRROR ? 0xFF7DD3FC : 0xFFCBD5E1);
        }
    }

    private void updateDockAutoHideUi() {
        if (mBtnPanelHideDock != null) {
            mBtnPanelHideDock.setText(mDockAutoHide ? "📌 Dock自动隐藏" : "📌 Dock固定显示");
            mBtnPanelHideDock.setTextColor(mDockAutoHide ? 0xFF7DD3FC : 0xFFCBD5E1);
        }
    }

    private void triggerQuickSplitOrMenu() {
        setPanelOpen(false);
        if (mLastStatus != null && mLastStatus.splitActive == 1) {
            setSplitMenuOpen(!mSplitMenuOpen);
            return;
        }
        mBg.execute(() -> {
            boolean ok = BridgeClient.toggleQuickSplit();
            mHandler.post(() -> {
                if (ok) {
                    Toast.makeText(BridgeService.this,
                            (mOverlayH > mOverlayW ? "已开启上下分屏（拖动分割条可调比例）" : "已开启左右分屏（拖动分割条可调比例）"),
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(BridgeService.this,
                            "请先打开应用，或在桌面长按/右键图标选择「分屏：放到左/右屏」",
                            Toast.LENGTH_LONG).show();
                }
                pollRemoteStateAsync();
            });
        });
    }

    private void setSplitMenuOpen(boolean open) {
        if (open && (mLastStatus == null || mLastStatus.splitActive == 0)) open = false;
        mSplitMenuOpen = open;
        if (open) setTaskBubbleOpen(false);
        if (mSplitMenuRoot != null) {
            mSplitMenuRoot.setVisibility(open ? View.VISIBLE : View.GONE);
            if (open) {
                repositionSplitMenu();
                mHandler.removeCallbacks(mAutoHideDockTask);
            } else {
                evaluateDockAutoHide(false);
            }
        }
        bringCursorToFront();
    }

    private void updateSplitUi(BridgeClient.Status st) {
        boolean active = st != null && st.connected && st.vdId > 0 && st.mirrorMode != 1 && st.splitActive == 1;
        syncOverlayHostSurface(false);
        if (mBtnDockSplit != null) {
            mBtnDockSplit.setText(active ? "◫ 分屏*" : "◫ 分屏");
            GradientDrawable bg = (GradientDrawable) mBtnDockSplit.getBackground();
            bg.setColor(active ? 0xCC0284C7 : 0x26FFFFFF);
        }
        if (mBtnPanelSplit != null) {
            mBtnPanelSplit.setText(active ? "◫ 退出分屏" : "◫ 一键分屏");
            mBtnPanelSplit.setTextColor(active ? 0xFF7DD3FC : 0xFFCBD5E1);
        }
        if (mSplitSwapBtn != null) {
            mSplitSwapBtn.setText(mOverlayH > mOverlayW ? "⇅ 上下互换" : "⇄ 左右互换");
        }
        int curPct = (st != null && st.splitRatioPct > 0) ? st.splitRatioPct : 50;
        for (int i = 0; i < SPLIT_PRESET_PCTS.length; i++) {
            TextView tv = mSplitRatioBtns[i];
            if (tv == null) continue;
            boolean sel = Math.abs(curPct - SPLIT_PRESET_PCTS[i]) <= 3;
            tv.setTextColor(sel ? 0xFF7DD3FC : 0xFFE2E8F0);
        }

        boolean singleEmpty = st != null
                && ((st.splitSlot0Pkg == null || st.splitSlot0Pkg.isEmpty())
                        != (st.splitSlot1Pkg == null || st.splitSlot1Pkg.isEmpty()));
        boolean hasTwoOrSlot = active && (!mIsOnLauncherHome || singleEmpty);
        if (mSplitDividerRoot != null) {
            if (hasTwoOrSlot) {
                if (mSplitDividerRoot.getVisibility() != View.VISIBLE) {
                    mSplitDividerRoot.setVisibility(View.VISIBLE);
                }
                if (!mSplitDragging) {
                    positionSplitDividerAt(Math.max(25, Math.min(75, curPct)) / 100f, false);
                }
            } else {
                mSplitDividerRoot.setVisibility(View.GONE);
                if (mSplitMenuOpen) setSplitMenuOpen(false);
            }
        }
        bringCursorToFront();
    }

    private void repositionSplitDivider() {
        int pct = (mLastStatus != null && mLastStatus.splitRatioPct > 0) ? mLastStatus.splitRatioPct : 50;
        positionSplitDividerAt(Math.max(25, Math.min(75, pct)) / 100f, mSplitDragging);
    }

    private void positionSplitDividerAt(float ratio, boolean dragging) {
        if (mSplitDividerRoot == null || mSplitDividerLp == null || mExtCtx == null) return;
        int surfW = Math.max(600, mOverlayW);
        int surfH = Math.max(600, mOverlayH);
        if (mInLauncherHost && sLauncherHost != null && sLauncherHost.getWidth() > 0 && sLauncherHost.getHeight() > 0) {
            surfW = sLauncherHost.getWidth();
            surfH = sLauncherHost.getHeight();
        }
        boolean port = surfH > surfW;
        int w = port ? dp(mExtCtx, 80) : dp(mExtCtx, 26);
        int h = port ? dp(mExtCtx, 26) : dp(mExtCtx, 80);
        float r = Math.max(0.25f, Math.min(0.75f, ratio));
        int pct = Math.round(r * 100f);

        int x = port ? (surfW - w) / 2 : Math.max(0, Math.min(surfW - w, Math.round(surfW * r) - w / 2));
        int y = port ? Math.max(0, Math.min(surfH - h, Math.round(surfH * r) - h / 2)) : (surfH - h) / 2;

        if (mSplitDividerPill != null) {
            mSplitDividerPill.setText(port ? ("⋯ " + pct + "%") : ("⋮\n" + pct));
            Drawable bg = mSplitDividerPill.getBackground();
            if (bg instanceof GradientDrawable) {
                ((GradientDrawable) bg).setStroke(dp(mExtCtx, dragging ? 2 : 1), dragging ? 0xFF38BDF8 : 0x8838BDF8);
                ((GradientDrawable) bg).setColor(dragging ? 0xF50284C7 : 0xE00F172A);
            }
        }

        mSplitDividerLp.width = w;
        mSplitDividerLp.height = h;
        mSplitDividerLp.x = x;
        mSplitDividerLp.y = y;
        mSplitDividerPortrait = port;

        if (mInLauncherHost) {
            ViewGroup.LayoutParams raw = mSplitDividerRoot.getLayoutParams();
            if (raw instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) raw;
                flp.gravity = Gravity.TOP | Gravity.LEFT;
                flp.width = w;
                flp.height = h;
                flp.leftMargin = x;
                flp.topMargin = y;
                mSplitDividerRoot.setLayoutParams(flp);
            }
        } else if (mExtWm != null) {
            try { mExtWm.updateViewLayout(mSplitDividerRoot, mSplitDividerLp); } catch (Throwable ignored) {}
        }
    }

    private void repositionSplitMenu() {
        if (mSplitMenuRoot == null || mSplitMenuLp == null || mExtCtx == null) return;
        int surfW = Math.max(600, mOverlayW);
        int surfH = Math.max(600, mOverlayH);
        if (mInLauncherHost && sLauncherHost != null && sLauncherHost.getWidth() > 0 && sLauncherHost.getHeight() > 0) {
            surfW = sLauncherHost.getWidth();
            surfH = sLauncherHost.getHeight();
        }
        mSplitMenuRoot.measure(
                View.MeasureSpec.makeMeasureSpec(surfW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(surfH, View.MeasureSpec.AT_MOST));
        int mw = Math.max(dp(mExtCtx, 220), mSplitMenuRoot.getMeasuredWidth());
        int mh = Math.max(dp(mExtCtx, 96), mSplitMenuRoot.getMeasuredHeight());
        int pct = (mLastStatus != null && mLastStatus.splitRatioPct > 0) ? mLastStatus.splitRatioPct : 50;
        float r = Math.max(0.25f, Math.min(0.75f, pct / 100f));
        boolean port = surfH > surfW;
        int x = port ? (surfW - mw) / 2 : Math.max(dp(mExtCtx, 12), Math.min(surfW - mw - dp(mExtCtx, 12), Math.round(surfW * r) - mw / 2));
        int y = port ? Math.max(dp(mExtCtx, 12), Math.min(surfH - mh - dp(mExtCtx, 80), Math.round(surfH * r) - mh - dp(mExtCtx, 22)))
                     : Math.max(dp(mExtCtx, 12), (surfH - mh) / 2 - dp(mExtCtx, 90));
        mSplitMenuLp.x = x;
        mSplitMenuLp.y = y;
        if (mInLauncherHost) {
            ViewGroup.LayoutParams raw = mSplitMenuRoot.getLayoutParams();
            if (raw instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) raw;
                flp.gravity = Gravity.TOP | Gravity.LEFT;
                flp.leftMargin = x;
                flp.topMargin = y;
                mSplitMenuRoot.setLayoutParams(flp);
            }
        } else if (mExtWm != null) {
            try { mExtWm.updateViewLayout(mSplitMenuRoot, mSplitMenuLp); } catch (Throwable ignored) {}
        }
    }

    private void bringCursorToFront() {
        if (mCursorView == null) return;
        try {
            if (mInLauncherHost) {
                mCursorView.bringToFront();
            } else if (mExtWm != null && mCursorView.getParent() != null) {
                mCursorView.setElevation(dp(mExtCtx != null ? mExtCtx : this, 48));
            }
        } catch (Throwable ignored) {}
    }

    private void buildCursorOverlay(Context ctx, BridgeClient.Status st) {
        mCursorView = new CursorView(ctx);
        mCursorView.setElevation(dp(ctx, 48));
        mCursorView.setTranslationZ(dp(ctx, 48));
        mCursorLp = new WindowManager.LayoutParams(
                44, 44,
                mWinType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        mCursorLp.gravity = Gravity.TOP | Gravity.LEFT;
        mCursorLp.x = Math.round(st.cursorX) - 6;
        mCursorLp.y = Math.round(st.cursorY) - 4;
        mCursorView.setVisibility(View.GONE);
    }

    private void addGridTile(LinearLayout row, View tileRoot, boolean marginRight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (marginRight && mExtCtx != null) lp.rightMargin = dp(mExtCtx, 10);
        row.addView(tileRoot, lp);
    }

    private TextView makeFooterChip(Context ctx, String txt) {
        TextView tv = new TextView(ctx);
        tv.setText(txt);
        tv.setTextColor(0xFFE2E8F0);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x28FFFFFF);
        bg.setCornerRadius(dp(ctx, 10));
        tv.setBackground(bg);
        return tv;
    }

    private void setPanelOpen(boolean open) {
        mHandler.removeCallbacks(mOutsideDismissTask);
        if (mPanelOpen == open && (mPanelRoot == null || (open && mPanelRoot.getVisibility() == View.VISIBLE))) {
            return;
        }
        mPanelOpen = open;
        updateTrayPillBackground(open);
        if (open) {
            setSplitMenuOpen(false);
            setTaskBubbleOpen(false);
            cancelAutoHideTimer();
            if (mDockHidden) applyDockHiddenAnimated(false);
        } else {
            evaluateDockAutoHide(false);
        }
        if (mPanelRoot == null || mPanelCard == null || mExtCtx == null) return;

        mPanelCard.animate().cancel();
        if (open) {
            mPanelRoot.setVisibility(View.VISIBLE);
            repositionPanel();
            refreshLocalHardwareViews();
            pollRemoteStateAsync();
            mPanelCard.setAlpha(0f);
            mPanelCard.setTranslationY(dp(mExtCtx, 18));
            mPanelCard.setScaleX(0.94f);
            mPanelCard.setScaleY(0.94f);
            mPanelCard.post(() -> {
                if (!mPanelOpen || mPanelCard == null) return;
                int w = mPanelCard.getWidth();
                int h = mPanelCard.getHeight();
                if (w > 0 && h > 0) {
                    mPanelCard.setPivotX(w * 0.85f);
                    mPanelCard.setPivotY(h);
                }
                mPanelCard.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(200L)
                        .setInterpolator(new DecelerateInterpolator(1.6f))
                        .withEndAction(this::bringCursorToFront)
                        .start();
            });
        } else {
            int w = mPanelCard.getWidth();
            int h = mPanelCard.getHeight();
            if (w > 0 && h > 0) {
                mPanelCard.setPivotX(w * 0.85f);
                mPanelCard.setPivotY(h);
            }
            mPanelCard.animate()
                    .alpha(0f)
                    .translationY(dp(mExtCtx, 14))
                    .scaleX(0.95f)
                    .scaleY(0.95f)
                    .setDuration(150L)
                    .setInterpolator(new AccelerateInterpolator(1.3f))
                    .withEndAction(() -> {
                        if (!mPanelOpen && mPanelRoot != null) {
                            mPanelRoot.setVisibility(View.GONE);
                        }
                    })
                    .start();
        }
    }

    private void buildTaskBubbleOverlay(Context ctx) {
        mTaskBubbleRoot = new LinearLayout(ctx);
        mTaskBubbleRoot.setOrientation(LinearLayout.VERTICAL);
        mTaskBubbleRoot.setGravity(Gravity.LEFT);
        mTaskBubbleRoot.setClipChildren(false);
        mTaskBubbleRoot.setClipToPadding(false);
        mTaskBubbleRoot.setVisibility(View.GONE);

        mTaskBubbleCard = new LinearLayout(ctx);
        mTaskBubbleCard.setOrientation(LinearLayout.VERTICAL);
        mTaskBubbleCard.setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8));
        mTaskBubbleCard.setElevation(dp(ctx, 18));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xF20F172A);
        cardBg.setCornerRadius(dp(ctx, 16));
        cardBg.setStroke(1, 0x55FFFFFF);
        mTaskBubbleCard.setBackground(cardBg);

        // Header: icon + app title + current state chip
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(ctx, 6), dp(ctx, 3), dp(ctx, 6), dp(ctx, 7));

        mBubbleIcon = new ImageView(ctx);
        mBubbleIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20));
        icLp.rightMargin = dp(ctx, 8);
        header.addView(mBubbleIcon, icLp);

        mBubbleTitle = new TextView(ctx);
        mBubbleTitle.setTextColor(0xFFF8FAFC);
        mBubbleTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        mBubbleTitle.getPaint().setFakeBoldText(true);
        mBubbleTitle.setSingleLine(true);
        mBubbleTitle.setEllipsize(TextUtils.TruncateAt.END);
        mBubbleTitle.setMaxWidth(dp(ctx, 130));
        LinearLayout.LayoutParams ttLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        ttLp.rightMargin = dp(ctx, 6);
        header.addView(mBubbleTitle, ttLp);

        mBubbleModeTag = new TextView(ctx);
        mBubbleModeTag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        mBubbleModeTag.setTextColor(0xFFBAE6FD);
        mBubbleModeTag.setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2));
        GradientDrawable tagBg = new GradientDrawable();
        tagBg.setColor(0x440284C7);
        tagBg.setCornerRadius(dp(ctx, 8));
        mBubbleModeTag.setBackground(tagBg);
        header.addView(mBubbleModeTag);
        mTaskBubbleCard.addView(header);

        View sep = new View(ctx);
        sep.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        sepLp.bottomMargin = dp(ctx, 5);
        mTaskBubbleCard.addView(sep, sepLp);

        // Action 1: Exit accidental Freeform small window back to fullscreen (enabling Freeform is disabled in DeX)
        mBubbleFreeformBtn = makeBubbleRowBtn(ctx, "⬜ 退出小窗（恢复全屏）", 0xFFE2E8F0, 0x3838BDF8);
        mBubbleFreeformBtn.setVisibility(View.GONE);
        mBubbleFreeformBtn.setOnClickListener(v -> {
            BridgeClient.TaskEntry target = mBubbleTask;
            String targetLbl = mBubbleLabel;
            setTaskBubbleOpen(false);
            if (target == null || !target.isFreeform()) return;
            mBg.execute(() -> {
                int res = BridgeClient.toggleTaskFreeform(target.taskId, target.pkg, 0);
                mHandler.post(() -> {
                    Toast.makeText(BridgeService.this,
                            res == 0 ? ("已恢复全屏：" + targetLbl) : ("恢复全屏失败：" + targetLbl),
                            Toast.LENGTH_SHORT).show();
                    pollRemoteStateAsync();
                });
            });
        });
        mTaskBubbleCard.addView(mBubbleFreeformBtn);

        // Action 2: Quick split left/top or right/bottom
        LinearLayout splitRow = new LinearLayout(ctx);
        splitRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        srLp.topMargin = dp(ctx, 4);

        mBubbleSplit0Btn = makeBubbleRowBtn(ctx, "◫ 放左屏", 0xFFBAE6FD, 0x380284C7);
        mBubbleSplit0Btn.setGravity(Gravity.CENTER);
        mBubbleSplit0Btn.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6));
        mBubbleSplit0Btn.setOnClickListener(v -> {
            BridgeClient.TaskEntry target = mBubbleTask;
            setTaskBubbleOpen(false);
            if (target == null) return;
            mBg.execute(() -> {
                BridgeClient.splitAction(BridgeClient.SPLIT_ASSIGN_LEFT_TOP, target.taskId, target.pkg, null);
                mHandler.post(this::pollRemoteStateAsync);
            });
        });
        LinearLayout.LayoutParams s0Lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        s0Lp.rightMargin = dp(ctx, 5);
        splitRow.addView(mBubbleSplit0Btn, s0Lp);

        mBubbleSplit1Btn = makeBubbleRowBtn(ctx, "◫ 放右屏", 0xFFBAE6FD, 0x380284C7);
        mBubbleSplit1Btn.setGravity(Gravity.CENTER);
        mBubbleSplit1Btn.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6));
        mBubbleSplit1Btn.setOnClickListener(v -> {
            BridgeClient.TaskEntry target = mBubbleTask;
            setTaskBubbleOpen(false);
            if (target == null) return;
            mBg.execute(() -> {
                BridgeClient.splitAction(BridgeClient.SPLIT_ASSIGN_RIGHT_BOTTOM, target.taskId, target.pkg, null);
                mHandler.post(this::pollRemoteStateAsync);
            });
        });
        splitRow.addView(mBubbleSplit1Btn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        mTaskBubbleCard.addView(splitRow, srLp);

        // Action 3: Close app
        mBubbleCloseBtn = makeBubbleRowBtn(ctx, "✕ 关闭应用", 0xFFFCA5A5, 0x36EF4444);
        LinearLayout.LayoutParams clLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clLp.topMargin = dp(ctx, 4);
        mBubbleCloseBtn.setOnClickListener(v -> {
            BridgeClient.TaskEntry target = mBubbleTask;
            String targetLbl = mBubbleLabel;
            setTaskBubbleOpen(false);
            if (target == null) return;
            mBg.execute(() -> {
                boolean ok = target.taskId > 0 && BridgeClient.removeTask(target.taskId);
                mHandler.post(() -> {
                    Toast.makeText(BridgeService.this,
                            ok ? ("已关闭：" + targetLbl) : ("暂无运行任务：" + targetLbl),
                            Toast.LENGTH_SHORT).show();
                    pollRemoteStateAsync();
                });
            });
        });
        mTaskBubbleCard.addView(mBubbleCloseBtn, clLp);

        mTaskBubbleRoot.addView(mTaskBubbleCard, new LinearLayout.LayoutParams(
                dp(ctx, 214), LinearLayout.LayoutParams.WRAP_CONTENT));

        // Downward pointer triangle like Android launcher shortcut bubble
        mBubbleTriangle = new View(ctx) {
            private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path mPath = new Path();
            {
                mFill.setColor(0xF20F172A);
                mFill.setStyle(Paint.Style.FILL);
                mStroke.setColor(0x55FFFFFF);
                mStroke.setStyle(Paint.Style.STROKE);
                mStroke.setStrokeWidth(1.5f);
            }
            @Override
            protected void onDraw(Canvas canvas) {
                float w = getWidth();
                float h = getHeight();
                mPath.reset();
                mPath.moveTo(0f, 0f);
                mPath.lineTo(w, 0f);
                mPath.lineTo(w * 0.5f, h);
                mPath.close();
                canvas.drawPath(mPath, mFill);
                canvas.drawLine(0f, 0f, w * 0.5f, h, mStroke);
                canvas.drawLine(w, 0f, w * 0.5f, h, mStroke);
            }
        };
        mTaskBubbleRoot.addView(mBubbleTriangle, new LinearLayout.LayoutParams(dp(ctx, 18), dp(ctx, 9)));

        mTaskBubbleLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                mWinType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        mTaskBubbleLp.gravity = Gravity.TOP | Gravity.LEFT;
        mTaskBubbleRoot.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE && mTaskBubbleOpen) {
                setTaskBubbleOpen(false);
            }
            return false;
        });
    }

    private TextView makeBubbleRowBtn(Context ctx, String text, int textColor, int hoverColor) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setSingleLine(true);
        tv.setPadding(dp(ctx, 10), dp(ctx, 7), dp(ctx, 10), dp(ctx, 7));

        GradientDrawable hi = new GradientDrawable();
        hi.setColor(hoverColor);
        hi.setCornerRadius(dp(ctx, 10));
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(0x16FFFFFF);
        normal.setCornerRadius(dp(ctx, 10));
        android.graphics.drawable.StateListDrawable sld = new android.graphics.drawable.StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, hi);
        sld.addState(new int[]{android.R.attr.state_hovered}, hi);
        sld.addState(new int[]{}, normal);
        tv.setBackground(sld);
        return tv;
    }

    private void showTaskBubble(View anchor, BridgeClient.TaskEntry t, String label, Drawable icon) {
        if (mTaskBubbleRoot == null || mExtCtx == null || t == null) return;
        mLastBubbleOpenMs = SystemClock.uptimeMillis();
        mBubbleTask = t;
        mBubbleLabel = (label != null && !label.isEmpty()) ? label : t.pkg;
        setPanelOpen(false);
        setSplitMenuOpen(false);

        if (mBubbleIcon != null) mBubbleIcon.setImageDrawable(cloneDrawable(icon));
        if (mBubbleTitle != null) mBubbleTitle.setText(mBubbleLabel);
        if (mBubbleModeTag != null) {
            mBubbleModeTag.setText(t.isFreeform() ? "小窗" : (t.isInSplit() ? "分屏" : (t.isRunning() ? "运行中" : "后台")));
        }
        if (mBubbleFreeformBtn != null) {
            mBubbleFreeformBtn.setVisibility(t.isFreeform() ? View.VISIBLE : View.GONE);
            mBubbleFreeformBtn.setText("⬜ 退出小窗（恢复全屏）");
        }
        boolean port = mOverlayH > mOverlayW;
        if (mBubbleSplit0Btn != null) mBubbleSplit0Btn.setText(port ? "◫ 放上屏" : "◫ 放左屏");
        if (mBubbleSplit1Btn != null) mBubbleSplit1Btn.setText(port ? "◫ 放下屏" : "◫ 放右屏");
        if (mBubbleCloseBtn != null) {
            mBubbleCloseBtn.setEnabled(t.taskId > 0);
            mBubbleCloseBtn.setAlpha(t.taskId > 0 ? 1f : 0.45f);
        }

        // Compute anchor horizontal center on virtual display
        int surfW = Math.max(600, mOverlayW);
        if (mInLauncherHost && sLauncherHost != null && sLauncherHost.getWidth() > 0) {
            surfW = sLauncherHost.getWidth();
        }
        int anchorCx = surfW / 2;
        if (anchor != null && mDockRoot != null && mDockRoot.getWidth() > 0) {
            int[] dockLoc = new int[2];
            int[] slotLoc = new int[2];
            mDockRoot.getLocationOnScreen(dockLoc);
            anchor.getLocationOnScreen(slotLoc);
            int relX = slotLoc[0] - dockLoc[0];
            int dockLeft = mInLauncherHost ? mDockRoot.getLeft() : (surfW - mDockRoot.getWidth()) / 2;
            anchorCx = dockLeft + relX + anchor.getWidth() / 2;
        }
        mBubbleAnchorCenterX = anchorCx;
        setTaskBubbleOpen(true);
    }

    private void setTaskBubbleOpen(boolean open) {
        if (open && mBubbleTask == null) open = false;
        mTaskBubbleOpen = open;
        if (mTaskBubbleRoot != null) {
            mTaskBubbleRoot.setVisibility(open ? View.VISIBLE : View.GONE);
            if (open) {
                repositionTaskBubble();
                mHandler.removeCallbacks(mAutoHideDockTask);
                if (mTaskBubbleCard != null) {
                    mTaskBubbleCard.animate().cancel();
                    mTaskBubbleCard.setAlpha(0f);
                    mTaskBubbleCard.setTranslationY(dp(mExtCtx, 10));
                    mTaskBubbleCard.setScaleX(0.92f);
                    mTaskBubbleCard.setScaleY(0.92f);
                    mTaskBubbleCard.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(150L)
                            .setInterpolator(new DecelerateInterpolator(1.5f))
                            .withEndAction(this::bringCursorToFront)
                            .start();
                }
            } else {
                evaluateDockAutoHide(false);
            }
        }
        bringCursorToFront();
    }

    private void repositionTaskBubble() {
        if (mTaskBubbleRoot == null || mTaskBubbleLp == null || mExtCtx == null) return;
        int surfW = Math.max(600, mOverlayW);
        int surfH = Math.max(600, mOverlayH);
        if (mInLauncherHost && sLauncherHost != null && sLauncherHost.getWidth() > 0 && sLauncherHost.getHeight() > 0) {
            surfW = sLauncherHost.getWidth();
            surfH = sLauncherHost.getHeight();
        }
        mTaskBubbleRoot.measure(
                View.MeasureSpec.makeMeasureSpec(surfW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(surfH, View.MeasureSpec.AT_MOST));
        int bw = Math.max(dp(mExtCtx, 214), mTaskBubbleRoot.getMeasuredWidth());
        int bh = Math.max(dp(mExtCtx, 140), mTaskBubbleRoot.getMeasuredHeight());

        int dockH = (mDockRoot != null && mDockRoot.getHeight() > 0) ? mDockRoot.getHeight() : dp(mExtCtx, 46);
        int anchorTop = (mInLauncherHost && mDockRoot != null && mDockRoot.getHeight() > 0)
                ? mDockRoot.getTop()
                : (surfH - dp(mExtCtx, 10) - dockH);

        int margin = dp(mExtCtx, 12);
        int x = Math.max(margin, Math.min(surfW - bw - margin, mBubbleAnchorCenterX - bw / 2));
        int y = Math.max(margin, anchorTop - bh - dp(mExtCtx, 4));

        if (mBubbleTriangle != null) {
            int triW = dp(mExtCtx, 18);
            int triX = Math.max(dp(mExtCtx, 16), Math.min(bw - triW - dp(mExtCtx, 16), (mBubbleAnchorCenterX - x) - triW / 2));
            mBubbleTriangle.setTranslationX(triX);
        }

        mTaskBubbleLp.x = x;
        mTaskBubbleLp.y = y;
        if (mInLauncherHost) {
            ViewGroup.LayoutParams raw = mTaskBubbleRoot.getLayoutParams();
            if (raw instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) raw;
                flp.gravity = Gravity.TOP | Gravity.LEFT;
                flp.leftMargin = x;
                flp.topMargin = y;
                mTaskBubbleRoot.setLayoutParams(flp);
            }
        } else if (mExtWm != null) {
            try { mExtWm.updateViewLayout(mTaskBubbleRoot, mTaskBubbleLp); } catch (Throwable ignored) {}
        }
    }

    private void updateTrayPillBackground(boolean active) {
        if (mTrayBtn == null || mExtCtx == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(active ? 0x45A6D8E8 : 0x1EFFFFFF);
        bg.setCornerRadius(dp(mExtCtx, 14));
        if (active) bg.setStroke(1, 0x80A6D8E8);
        mTrayBtn.setBackground(bg);
    }

    private void repositionDock() {
        if (mDockRoot == null || mDockLp == null || mExtCtx == null) return;
        try {
            int surfW = mOverlayW;
            if (mInLauncherHost && sLauncherHost != null && sLauncherHost.getWidth() > 0) {
                surfW = sLauncherHost.getWidth();
            }
            int dw = mDockRoot.getWidth();
            int maxW = Math.max(200, surfW - dp(mExtCtx, 20));
            int targetW = dw > maxW ? maxW : WindowManager.LayoutParams.WRAP_CONTENT;
            int bottomMargin = dp(mExtCtx, 10);
            mDockLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            mDockLp.width = targetW;
            mDockLp.x = 0;
            mDockLp.y = bottomMargin;
            if (mInLauncherHost) {
                ViewGroup.LayoutParams raw = mDockRoot.getLayoutParams();
                if (raw instanceof FrameLayout.LayoutParams) {
                    FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) raw;
                    int wantGravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                    if (flp.width != targetW || flp.gravity != wantGravity
                            || flp.leftMargin != 0 || flp.topMargin != 0 || flp.bottomMargin != bottomMargin) {
                        flp.gravity = wantGravity;
                        flp.width = targetW;
                        flp.leftMargin = 0;
                        flp.topMargin = 0;
                        flp.bottomMargin = bottomMargin;
                        mDockRoot.setLayoutParams(flp);
                    }
                }
            } else if (mExtWm != null) {
                mExtWm.updateViewLayout(mDockRoot, mDockLp);
            }
        } catch (Throwable ignored) {}
    }

    private void repositionPanel() {
        if (mPanelRoot == null || mPanelLp == null || mExtCtx == null) return;
        try {
            int surfW = mOverlayW;
            int surfH = mOverlayH;
            if (mInLauncherHost && sLauncherHost != null) {
                if (sLauncherHost.getWidth() > 0) surfW = sLauncherHost.getWidth();
                if (sLauncherHost.getHeight() > 0) surfH = sLauncherHost.getHeight();
            }
            int pw = mPanelRoot.getWidth();
            int ph = mPanelRoot.getHeight();
            if (pw <= 0) pw = dp(mExtCtx, 338);
            if (ph <= 0) ph = dp(mExtCtx, 320);

            int dockW = (mDockRoot != null && mDockRoot.getWidth() > 0) ? mDockRoot.getWidth() : dp(mExtCtx, 520);
            int dockH = (mDockRoot != null && mDockRoot.getHeight() > 0) ? mDockRoot.getHeight() : dp(mExtCtx, 46);
            int dockBottomMargin = dp(mExtCtx, 10);
            int anchorRight = (mInLauncherHost && mDockRoot != null && mDockRoot.getWidth() > 0)
                    ? mDockRoot.getRight()
                    : ((surfW + dockW) / 2);
            int anchorTop = (mInLauncherHost && mDockRoot != null && mDockRoot.getHeight() > 0)
                    ? mDockRoot.getTop()
                    : (surfH - dockBottomMargin - dockH);

            int x = Math.min(surfW - pw - dp(mExtCtx, 14), Math.max(dp(mExtCtx, 14), anchorRight - pw));
            int y = Math.max(dp(mExtCtx, 14), anchorTop - ph - dp(mExtCtx, 10));
            mPanelLp.x = x;
            mPanelLp.y = y;
            if (mInLauncherHost) {
                ViewGroup.LayoutParams raw = mPanelRoot.getLayoutParams();
                if (raw instanceof FrameLayout.LayoutParams) {
                    FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) raw;
                    if (flp.leftMargin != x || flp.topMargin != y) {
                        flp.gravity = Gravity.TOP | Gravity.LEFT;
                        flp.leftMargin = x;
                        flp.topMargin = y;
                        mPanelRoot.setLayoutParams(flp);
                    }
                }
            } else if (mExtWm != null) {
                mExtWm.updateViewLayout(mPanelRoot, mPanelLp);
            }
        } catch (Throwable ignored) {}
    }

    private void updateInputToggleUi(int inputToExt) {
        if (mBtnToggle != null) {
            mBtnToggle.setText(inputToExt == 1 ? "⇄ 外接" : "⇄ 平板");
            GradientDrawable gd = (GradientDrawable) mBtnToggle.getBackground();
            gd.setColor(inputToExt == 1 ? 0xCC2563EB : 0xCCEA580C);
        }
        if (mTileInput != null) {
            mTileInput.setActive(inputToExt == 1, inputToExt == 1 ? "外接扩展屏" : "平板本机");
        }
    }

    private void updateTaskStrip(List<BridgeClient.TaskEntry> tasks) {
        if (mTaskStrip == null || mExtCtx == null) return;
        int surfW = mOverlayW;
        if (mInLauncherHost && sLauncherHost != null && sLauncherHost.getWidth() > 0) {
            surfW = sLauncherHost.getWidth();
        }
        int fixedW = dp(mExtCtx, 390);
        int slotW = Math.max(1, dp(mExtCtx, 42));
        int availW = Math.max(0, surfW - dp(mExtCtx, 28) - fixedW);
        int maxShown = Math.max(2, Math.min(9, availW / slotW));
        int limit = Math.min(maxShown, tasks.size());

        StringBuilder sig = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            BridgeClient.TaskEntry t = tasks.get(i);
            sig.append(t.taskId).append(':').append(t.pkg).append(':').append(t.flags).append(';');
        }
        String s = sig.toString();
        if (s.equals(mLastTaskSig)) return;
        mLastTaskSig = s;

        mTaskStrip.removeAllViews();
        if (mTaskDivider != null) {
            mTaskDivider.setVisibility(limit > 0 ? View.VISIBLE : View.GONE);
        }

        PackageManager pm = getPackageManager();
        for (int i = 0; i < limit; i++) {
            BridgeClient.TaskEntry t = tasks.get(i);
            Drawable icon = getCachedIcon(pm, t.pkg);
            String label = getCachedLabel(pm, t.pkg);

            LinearLayout slot = new LinearLayout(mExtCtx);
            slot.setOrientation(LinearLayout.VERTICAL);
            slot.setGravity(Gravity.CENTER);
            slot.setPadding(dp(mExtCtx, 6), dp(mExtCtx, 3), dp(mExtCtx, 6), dp(mExtCtx, 3));
            GradientDrawable slotBg = new GradientDrawable();
            slotBg.setCornerRadius(dp(mExtCtx, 10));
            if (t.isTopExt()) {
                slotBg.setColor(0x36FFFFFF);
            } else if (t.onExt()) {
                slotBg.setColor(0x18FFFFFF);
            } else {
                slotBg.setColor(Color.TRANSPARENT);
            }
            slot.setBackground(slotBg);

            ImageView iv = new ImageView(mExtCtx);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (icon != null) iv.setImageDrawable(icon);
            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(dp(mExtCtx, 28), dp(mExtCtx, 28));
            slot.addView(iv, ivLp);

            View dot = new View(mExtCtx);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setCornerRadius(dp(mExtCtx, 2));
            if (t.isTopExt()) {
                dotBg.setColor(0xFF38BDF8);
            } else if (t.onExt()) {
                dotBg.setColor(0xFF94A3B8);
            } else if (t.isRunning()) {
                dotBg.setColor(0x7794A3B8);
            } else {
                dotBg.setColor(Color.TRANSPARENT);
            }
            dot.setBackground(dotBg);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                    t.isTopExt() ? dp(mExtCtx, 14) : dp(mExtCtx, 6), dp(mExtCtx, 3));
            dotLp.topMargin = dp(mExtCtx, 2);
            slot.addView(dot, dotLp);

            final boolean[] secDown = {false};
            final Runnable openBubble = () -> {
                long now = SystemClock.uptimeMillis();
                if (now - mLastBubbleOpenMs < 240L) return;
                showTaskBubble(slot, t, label, icon);
            };
            slot.setOnClickListener(v -> {
                setPanelOpen(false);
                setTaskBubbleOpen(false);
                boolean shouldMinimize = t.isTopExt() && !t.isInSplit()
                        && mLastStatus != null && mLastStatus.mirrorMode == BridgeClient.MIRROR_MODE_DEX;
                mBg.execute(() -> {
                    if (shouldMinimize) {
                        BridgeClient.minimizeToExtHome(t.taskId, t.pkg);
                    } else {
                        BridgeClient.switchToTask(t.taskId, t.pkg);
                    }
                    mHandler.postDelayed(this::pollRemoteStateAsync, 250);
                });
            });
            slot.setOnTouchListener((v, ev) -> {
                if (ev == null) return false;
                int act = ev.getActionMasked();
                if (act == MotionEvent.ACTION_DOWN
                        && (ev.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                    secDown[0] = true;
                    openBubble.run();
                    return true;
                }
                if (secDown[0]) {
                    if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                        secDown[0] = false;
                    }
                    return true;
                }
                return false;
            });
            slot.setOnGenericMotionListener((v, ev) -> {
                if (ev != null && ev.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS
                        && ev.getActionButton() == MotionEvent.BUTTON_SECONDARY) {
                    openBubble.run();
                    return true;
                }
                return false;
            });
            if (Build.VERSION.SDK_INT >= 23) {
                slot.setOnContextClickListener(v -> {
                    openBubble.run();
                    return true;
                });
            }
            slot.setOnLongClickListener(v -> {
                openBubble.run();
                return true;
            });

            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sLp.leftMargin = dp(mExtCtx, 2);
            sLp.rightMargin = dp(mExtCtx, 2);
            mTaskStrip.addView(slot, sLp);
        }
    }

    private void refreshLocalHardwareViews() {
        refreshTrayIconsOnly();
        if (mAm != null && mVolSeekBar != null && !mSeekingVol) {
            int max = Math.max(1, mAm.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            int cur = mAm.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (mVolSeekBar.getMax() != max) mVolSeekBar.setMax(max);
            mVolSeekBar.setProgress(cur);
        }
        if (mTrayBattView != null) mTrayBattView.setBattery(mBattPct, mBattCharging);
        if (mTrayBattPct != null) mTrayBattPct.setText(mBattPct + "%");
        if (mPanelBattView != null) mPanelBattView.setBattery(mBattPct, mBattCharging);
        if (mPanelBattText != null) {
            mPanelBattText.setText(mBattPct + "%" + (mBattCharging ? " · 充电中" : ""));
        }
    }

    private void refreshTrayIconsOnly() {
        boolean muted = false;
        int volPct = 50;
        if (mAm != null) {
            int max = Math.max(1, mAm.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            int cur = mAm.getStreamVolume(AudioManager.STREAM_MUSIC);
            try { muted = mAm.isStreamMute(AudioManager.STREAM_MUSIC) || cur == 0; } catch (Throwable ignored) {}
            volPct = Math.round(cur * 100f / max);
        }
        if (mTrayVolIcon != null) mTrayVolIcon.setVolumeLevel(muted ? 0 : (volPct > 50 ? 2 : 1));
        if (mVolSpeakerIcon != null) mVolSpeakerIcon.setVolumeLevel(muted ? 0 : (volPct > 50 ? 2 : 1));
    }

    private void refreshSysViews() {
        if (!mLastSys.valid) return;
        if (mTrayWifiIcon != null) {
            mTrayWifiIcon.setActiveState(mLastSys.wifiOn);
        }
        if (mTileWifi != null) {
            String sub = mLastSys.wifiOn
                    ? (mLastSys.wifiSsid.isEmpty() ? "已开启" : mLastSys.wifiSsid)
                    : "Wi-Fi";
            mTileWifi.setActive(mLastSys.wifiOn, sub);
        }
        if (mTileBt != null) {
            String sub = mLastSys.btOn
                    ? (mLastSys.btName.isEmpty() ? "未连接" : mLastSys.btName)
                    : "蓝牙";
            mTileBt.setActive(mLastSys.btOn, sub);
        }
        if (mTileAirplane != null) {
            mTileAirplane.setActive(mLastSys.airplaneOn, "飞行模式");
        }
        if (mTilePowerSave != null) {
            mTilePowerSave.setActive(mLastSys.powerSaveOn, "节能模式");
        }
    }

    private void execSysAndRefresh(int act, int arg, String str) {
        mBg.execute(() -> {
            BridgeClient.execSysAction(act, arg, str);
            try { Thread.sleep(220); } catch (InterruptedException ignored) {}
            BridgeClient.SysStatus sys = BridgeClient.getSysStatus();
            mHandler.post(() -> {
                if (sys != null && sys.valid) mLastSys = sys;
                refreshSysViews();
            });
        });
    }

    private void openExtSettings(String action) {
        setPanelOpen(false);
        if (mUsingA11yOverlay) {
            // Scheme B: accessibility overlay remains visible inside Settings on external display
            mBg.execute(() -> BridgeClient.execSysAction(BridgeClient.SYS_OPEN_SETTINGS, -1, action));
        } else {
            // Scheme A fallback: open Settings on tablet Display 0 so external screen isn't trapped
            Toast.makeText(this, "未开启无障碍：已在平板打开系统设置", Toast.LENGTH_SHORT).show();
            mBg.execute(() -> BridgeClient.execSysAction(BridgeClient.SYS_OPEN_SETTINGS, 0, action));
        }
    }

    private void openTabletMain() {
        setPanelOpen(false);
        try {
            Intent mi = new Intent(BridgeService.this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityOptions o0 = ActivityOptions.makeBasic();
            o0.setLaunchDisplayId(0);
            startActivity(mi, o0.toBundle());
        } catch (Throwable ignored) {}
    }

    private void openTabletTouchpad() {
        setPanelOpen(false);
        try {
            Intent ti = new Intent(BridgeService.this, TouchpadActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityOptions o0 = ActivityOptions.makeBasic();
            o0.setLaunchDisplayId(0);
            startActivity(ti, o0.toBundle());
        } catch (Throwable ignored) {}
    }

    private Drawable cloneDrawable(Drawable d) {
        if (d == null) return null;
        try {
            Drawable.ConstantState cs = d.getConstantState();
            if (cs != null) {
                return cs.newDrawable(getResources()).mutate();
            }
        } catch (Throwable ignored) {}
        return d;
    }

    private Drawable getCachedIcon(PackageManager pm, String pkg) {
        Drawable d = mIconCache.get(pkg);
        if (d != null) return cloneDrawable(d);
        try {
            d = pm.getApplicationIcon(pkg);
            if (d != null) {
                mIconCache.put(pkg, d);
                return cloneDrawable(d);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private String getCachedLabel(PackageManager pm, String pkg) {
        String lbl = mLabelCache.get(pkg);
        if (lbl != null) return lbl;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence cs = pm.getApplicationLabel(ai);
            if (cs != null) {
                lbl = cs.toString();
                mLabelCache.put(pkg, lbl);
                return lbl;
            }
        } catch (Throwable ignored) {}
        return pkg;
    }

    private void updateCursorOnExt(float x, float y, boolean pressing) {
        if (mCursorView == null || mCursorLp == null) return;
        try {
            if (!mCursorVisible) {
                mCursorVisible = true;
                mCursorView.setVisibility(View.VISIBLE);
            }
            mCursorView.setPressing(pressing);
            mCursorLp.x = Math.round(x) - 6;
            mCursorLp.y = Math.round(y) - 4;
            if (mInLauncherHost) {
                mCursorView.setTranslationX(mCursorLp.x);
                mCursorView.setTranslationY(mCursorLp.y);
                mCursorView.bringToFront();
            } else if (mExtWm != null) {
                mExtWm.updateViewLayout(mCursorView, mCursorLp);
            }
            mHandler.removeCallbacks(mHideCursorTask);
            mHandler.postDelayed(mHideCursorTask, 7000);
            if (mExtCtx != null && mDockAutoHide && !mIsOnLauncherHome && mOverlayH > 0) {
                int edgeZone = dp(mExtCtx, 18);
                int dockZone = dp(mExtCtx, 82);
                if (mDockHidden && y >= (mOverlayH - edgeZone)
                        && Math.abs(x - mOverlayW * 0.5f) <= dp(mExtCtx, 190)) {
                    wakeDockFromEdge();
                } else if (!mDockHidden && y >= (mOverlayH - dockZone)) {
                    mHandler.removeCallbacks(mAutoHideDockTask);
                    evaluateDockAutoHide(true);
                }
            }
        } catch (Throwable ignored) {}
    }

    private void setCursorVisible(boolean vis) {
        mCursorVisible = vis;
        if (mCursorView != null) {
            mCursorView.setVisibility(vis ? View.VISIBLE : View.GONE);
        }
    }

    public static void toggleBlackoutFromApp(Context ctx) {
        BridgeService svc = sInstance;
        if (svc == null || svc.mDestroyed) {
            ensureStarted(ctx);
            if (ctx != null) {
                Toast.makeText(ctx, "后台服务正在启动，请稍候再点", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        Runnable r = () -> {
            BridgeClient.Status st = svc.mLastStatus;
            if (st != null && st.connected && st.mirrorMode == 1 && st.extId > 0) {
                // In pure mirror mode, dim hardware backlight only (without black overlay that would mirror to TV)
                final boolean nextDim = !svc.mLastSys.screenDimmed;
                svc.safeBgExecute(() -> {
                    BridgeClient.execSysAction(BridgeClient.SYS_SCREEN_DIM, nextDim ? 1 : 0, null);
                    BridgeClient.SysStatus ns = BridgeClient.getSysStatus();
                    if (ns != null && ns.valid) svc.mLastSys = ns;
                    svc.safePost(() -> Toast.makeText(svc,
                            nextDim ? "🌙 已将主屏背光降至最低（再点恢复原亮度）" : "☀ 已恢复主屏亮度",
                            Toast.LENGTH_SHORT).show());
                });
                return;
            }
            if (st == null || !st.connected || st.vdId <= 0) {
                if (svc.mBlackoutActive) {
                    svc.setDexBlackout(false);
                } else {
                    Toast.makeText(svc, "请先接入 Type-C 外接显示器后再开启一键黑屏", Toast.LENGTH_SHORT).show();
                }
                return;
            }
            svc.toggleDexBlackout();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else if (svc.mHandler != null) svc.mHandler.post(r);
    }

    private void toggleDexBlackout() {
        boolean next = !mBlackoutActive;
        setDexBlackout(next);
        if (next) {
            setPanelOpen(false);
        }
    }

    private void setDexBlackout(boolean on) {
        if (on && mBlackoutActive && mBlackoutView != null) {
            updateBlackoutUi();
            return;
        }
        if (on) {
            if (!attachBlackoutOverlay()) {
                mBlackoutActive = false;
                updateBlackoutUi();
                Toast.makeText(this, "请开启无障碍或悬浮窗权限后使用一键黑屏", Toast.LENGTH_SHORT).show();
                return;
            }
            mBlackoutActive = true;
        } else {
            mBlackoutActive = false;
            removeBlackoutOverlay();
        }
        updateBlackoutUi();
        final int arg = on ? 1 : 0;
        safeBgExecute(() -> {
            BridgeClient.execSysAction(BridgeClient.SYS_SCREEN_DIM, arg, null);
            safePost(this::pollRemoteStateAsync);
        });
    }

    private boolean attachBlackoutOverlay() {
        if (mBlackoutView != null) return true;
        try {
            Display disp0 = (mDm != null) ? mDm.getDisplay(Display.DEFAULT_DISPLAY) : null;
            if (disp0 == null) return false;
            NavAssistService a11y = NavAssistService.getInstance();
            int type;
            Context ctx0;
            if (a11y != null) {
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
                ctx0 = a11y.createDisplayContext(disp0);
            } else if (Settings.canDrawOverlays(this)) {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
                ctx0 = createDisplayContext(disp0);
            } else {
                return false;
            }
            WindowManager wm0 = (WindowManager) ctx0.getSystemService(WINDOW_SERVICE);
            if (wm0 == null && a11y != null) wm0 = (WindowManager) a11y.getSystemService(WINDOW_SERVICE);
            if (wm0 == null) wm0 = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm0 == null) return false;

            final FrameLayout root = new FrameLayout(ctx0);
            root.setBackgroundColor(Color.BLACK);
            root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

            final TextView hint = new TextView(ctx0);
            hint.setText("🌙 平板已进入一键省电黑屏（最低亮度）\n双击平板屏幕 · 或在 Dock 控制中心关闭");
            hint.setTextColor(0x99FFFFFF);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            hint.setGravity(Gravity.CENTER);
            hint.setLineSpacing(dp(ctx0, 4), 1f);
            hint.setPadding(dp(ctx0, 24), dp(ctx0, 16), dp(ctx0, 24), dp(ctx0, 16));
            FrameLayout.LayoutParams hLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            root.addView(hint, hLp);

            if (mBlackoutHideHintTask != null && mHandler != null) {
                mHandler.removeCallbacks(mBlackoutHideHintTask);
            }
            mBlackoutHideHintTask = () -> {
                if (hint.getParent() != null) hint.setVisibility(View.GONE);
            };
            mLastBlackoutTapMs = 0L;
            root.setOnClickListener(v -> {
                long now = SystemClock.uptimeMillis();
                if (now - mLastBlackoutTapMs <= 450L) {
                    if (mHandler != null && mBlackoutHideHintTask != null) {
                        mHandler.removeCallbacks(mBlackoutHideHintTask);
                    }
                    setDexBlackout(false);
                    return;
                }
                mLastBlackoutTapMs = now;
                hint.setVisibility(View.VISIBLE);
                if (mHandler != null && mBlackoutHideHintTask != null) {
                    mHandler.removeCallbacks(mBlackoutHideHintTask);
                    mHandler.postDelayed(mBlackoutHideHintTask, 2600L);
                }
            });
            if (mHandler != null) mHandler.postDelayed(mBlackoutHideHintTask, 3200L);

            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type, flags, PixelFormat.OPAQUE);
            lp.gravity = Gravity.TOP | Gravity.START;
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            lp.setTitle("ExtBlackout");
            wm0.addView(root, lp);
            mBlackoutView = root;
            mBlackoutWm = wm0;
            return true;
        } catch (Throwable t) {
            if (mHandler != null && mBlackoutHideHintTask != null) {
                mHandler.removeCallbacks(mBlackoutHideHintTask);
            }
            mBlackoutHideHintTask = null;
            t.printStackTrace();
            return false;
        }
    }

    private void removeBlackoutOverlay() {
        if (mHandler != null && mBlackoutHideHintTask != null) {
            mHandler.removeCallbacks(mBlackoutHideHintTask);
        }
        mBlackoutHideHintTask = null;
        if (mBlackoutView != null && mBlackoutWm != null) {
            try { mBlackoutWm.removeViewImmediate(mBlackoutView); } catch (Throwable ignored) {}
        }
        mBlackoutView = null;
        mBlackoutWm = null;
    }

    private void updateBlackoutUi() {
        if (mBtnPanelBlackout != null) {
            mBtnPanelBlackout.setText(mBlackoutActive ? "☀ 已黑屏 · 点此恢复平板亮屏" : "🌙 一键黑屏 (平板最低亮度)");
            mBtnPanelBlackout.setTextColor(mBlackoutActive ? 0xFFFDE047 : 0xFFCBD5E1);
        }
    }

    private void detachExtOverlays(boolean keepBlackout) {
        cancelAutoHideTimer();
        if (!keepBlackout) {
            if (mBlackoutActive) {
                setDexBlackout(false);
            } else {
                removeBlackoutOverlay();
            }
        }
        removeOverlayView(mSplitDividerRoot);
        removeOverlayView(mSplitMenuRoot);
        removeOverlayView(mTaskBubbleRoot);
        removeOverlayView(mDockEdgeTrigger);
        removeOverlayView(mDockRoot);
        removeOverlayView(mPanelRoot);
        removeOverlayView(mCursorView);
        mInLauncherHost = false;
        mExtWm = null;
        mExtCtx = null;
        mUsingA11yOverlay = false;
        mWinType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        mDockRoot = null;
        mDockEdgeTrigger = null;
        mDockEdgePill = null;
        mDockEdgeLp = null;
        mDockHidden = false;
        mDockHovered = false;
        mSplitDividerRoot = null;
        mSplitDividerPill = null;
        mSplitDividerLp = null;
        mSplitDragging = false;
        mSplitMenuRoot = null;
        mSplitMenuLp = null;
        mSplitMenuOpen = false;
        mTaskBubbleRoot = null;
        mTaskBubbleCard = null;
        mBubbleTriangle = null;
        mTaskBubbleLp = null;
        mTaskBubbleOpen = false;
        mBubbleIcon = null;
        mBubbleTitle = null;
        mBubbleModeTag = null;
        mBubbleFreeformBtn = null;
        mBubbleSplit0Btn = null;
        mBubbleSplit1Btn = null;
        mBubbleCloseBtn = null;
        mBubbleTask = null;
        mSplitSwapBtn = null;
        java.util.Arrays.fill(mSplitRatioBtns, null);
        mBtnDockSplit = null;
        mBtnPanelSplit = null;
        mPanelRoot = null;
        mPanelCard = null;
        mPanelOpen = false;
        mTrayPressed = false;
        mHandler.removeCallbacks(mOutsideDismissTask);
        mBtnToggle = null;
        mBtnPanelFullscreen = null;
        mBtnPanelDex = null;
        mBtnPanelMirror = null;
        mBtnPanelHideDock = null;
        mBtnPanelBlackout = null;
        mTaskStrip = null;
        mTaskDivider = null;
        mTrayBtn = null;
        mTrayWifiIcon = null;
        mTrayVolIcon = null;
        mTrayBattView = null;
        mTrayBattPct = null;
        mTileWifi = null;
        mTileBt = null;
        mTileInput = null;
        mTileAirplane = null;
        mTileTouchpad = null;
        mTilePowerSave = null;
        mVolSpeakerIcon = null;
        mVolSeekBar = null;
        mPanelBattView = null;
        mPanelBattText = null;
        mCursorView = null;
        mCursorVisible = false;
        mAttachedVdId = -1;
        mOverlayW = 0;
        mOverlayH = 0;
        mOverlayDpi = 0;
        mLastTaskSig = "";
    }

    private static int dp(Context ctx, float dpVal) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dpVal, ctx.getResources().getDisplayMetrics()));
    }

    private static View makeVerticalDivider(Context ctx) {
        View div = new View(ctx);
        div.setBackgroundColor(0x38FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(1, dp(ctx, 22));
        lp.leftMargin = dp(ctx, 7);
        lp.rightMargin = dp(ctx, 7);
        div.setLayoutParams(lp);
        return div;
    }

    private static void enforceSingleLine(TextView tv) {
        tv.setSingleLine(true);
        tv.setMaxLines(1);
        tv.setIncludeFontPadding(false);
        tv.setEllipsize(TextUtils.TruncateAt.END);
    }

    private static TextView makeDockBtn(Context ctx, String txt, int color, int textColor) {
        TextView tv = new TextView(ctx);
        tv.setText(txt);
        tv.setTextColor(textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        tv.setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5));
        enforceSingleLine(tv);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(ctx, 13));
        tv.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(ctx, 3), 0, dp(ctx, 3), 0);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static class QuickTile {
        final LinearLayout root;
        final LinearLayout box;
        final View leftBtn;
        final View rightBtn;
        final View splitSep;
        final MiniIconView icon;
        final TextView arrow;
        final TextView label;
        final boolean hasArrow;
        final Context ctx;
        boolean active = false;

        QuickTile(Context ctx, int iconKind, String initialLabel, boolean hasArrow,
                  Runnable onToggle, Runnable onMore) {
            this.ctx = ctx;
            this.hasArrow = hasArrow;

            root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_HORIZONTAL);

            box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 44));
            boxLp.bottomMargin = dp(ctx, 5);
            root.addView(box, boxLp);

            LinearLayout leftWrap = new LinearLayout(ctx);
            leftWrap.setGravity(Gravity.CENTER);
            leftWrap.setOnClickListener(v -> { if (onToggle != null) onToggle.run(); });
            icon = new MiniIconView(ctx, iconKind, Color.WHITE);
            leftWrap.addView(icon, new LinearLayout.LayoutParams(dp(ctx, 18), dp(ctx, 18)));
            box.addView(leftWrap, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            leftBtn = leftWrap;

            if (hasArrow) {
                splitSep = new View(ctx);
                splitSep.setBackgroundColor(0x28FFFFFF);
                box.addView(splitSep, new LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.MATCH_PARENT));

                TextView arr = new TextView(ctx);
                arr.setText("›");
                arr.setTextColor(Color.WHITE);
                arr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
                arr.setGravity(Gravity.CENTER);
                arr.setOnClickListener(v -> { if (onMore != null) onMore.run(); });
                box.addView(arr, new LinearLayout.LayoutParams(dp(ctx, 32), LinearLayout.LayoutParams.MATCH_PARENT));
                arrow = arr;
                rightBtn = arr;
            } else {
                splitSep = null;
                arrow = null;
                rightBtn = null;
            }

            label = new TextView(ctx);
            label.setText(initialLabel);
            label.setTextColor(0xFFE2E8F0);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            root.addView(label, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            setActive(false, initialLabel);
        }

        private final GradientDrawable bg = new GradientDrawable();
        private boolean bgInit = false;

        void setActive(boolean on, String caption) {
            if (caption != null) label.setText(caption);
            if (bgInit && on == active) return;
            active = on;
            bgInit = true;

            bg.setColor(on ? 0xFFA6D8E8 : 0xFF2B3444);
            bg.setCornerRadius(dp(ctx, 8));
            bg.setStroke(1, on ? 0x44FFFFFF : 0x1AFFFFFF);
            box.setBackground(bg);

            int fg = on ? 0xFF0F172A : Color.WHITE;
            icon.setTint(fg);
            if (arrow != null) arrow.setTextColor(fg);
            if (splitSep != null) splitSep.setBackgroundColor(on ? 0x330F172A : 0x22FFFFFF);
        }
    }

    private static class MiniIconView extends View {
        static final int KIND_WIFI = 1;
        static final int KIND_BT = 2;
        static final int KIND_VOLUME = 3;
        static final int KIND_AIRPLANE = 4;
        static final int KIND_BATTERY_SAVER = 5;
        static final int KIND_DISPLAY_SWITCH = 6;
        static final int KIND_TOUCHPAD = 7;

        private final int mKind;
        private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mArcRect = new RectF();
        private final Path mPath = new Path();
        private int mColor;
        private int mVolLevel = 2; // 0=mute, 1=low, 2=high
        private boolean mActiveState = true;

        MiniIconView(Context ctx, int kind, int color) {
            super(ctx);
            mKind = kind;
            mColor = color;
        }

        void setTint(int c) {
            if (mColor != c) { mColor = c; invalidate(); }
        }

        void setVolumeLevel(int lvl) {
            if (mVolLevel != lvl) { mVolLevel = lvl; invalidate(); }
        }

        void setActiveState(boolean a) {
            if (mActiveState != a) { mActiveState = a; invalidate(); }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth() - getPaddingLeft() - getPaddingRight();
            float h = getHeight() - getPaddingTop() - getPaddingBottom();
            if (w <= 0 || h <= 0) return;
            c.save();
            c.translate(getPaddingLeft(), getPaddingTop());

            mP.setColor(mActiveState ? mColor : (mColor & 0x66FFFFFF));
            mP.setStrokeCap(Paint.Cap.ROUND);
            mP.setStrokeJoin(Paint.Join.ROUND);

            float cx = w * 0.5f;
            float cy = h * 0.5f;
            float s = Math.min(w, h);

            if (mKind == KIND_WIFI) {
                mP.setStyle(Paint.Style.STROKE);
                mP.setStrokeWidth(Math.max(1.8f, s * 0.11f));
                float oy = h * 0.80f;
                mArcRect.set(cx - s * 0.44f, oy - s * 0.62f, cx + s * 0.44f, oy + s * 0.26f);
                c.drawArc(mArcRect, -135, 90, false, mP);
                mArcRect.set(cx - s * 0.28f, oy - s * 0.40f, cx + s * 0.28f, oy + s * 0.16f);
                c.drawArc(mArcRect, -135, 90, false, mP);
                mP.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, oy - s * 0.04f, Math.max(1.5f, s * 0.09f), mP);
                if (!mActiveState) {
                    mP.setColor(mColor);
                    mP.setStyle(Paint.Style.STROKE);
                    c.drawLine(w * 0.18f, h * 0.18f, w * 0.82f, h * 0.82f, mP);
                }
            } else if (mKind == KIND_BT) {
                mP.setStyle(Paint.Style.STROKE);
                mP.setStrokeWidth(Math.max(1.7f, s * 0.10f));
                mPath.reset();
                mPath.moveTo(cx - s * 0.16f, cy - s * 0.20f);
                mPath.lineTo(cx + s * 0.18f, cy + s * 0.16f);
                mPath.lineTo(cx, cy + s * 0.36f);
                mPath.lineTo(cx, cy - s * 0.36f);
                mPath.lineTo(cx + s * 0.18f, cy - s * 0.16f);
                mPath.lineTo(cx - s * 0.16f, cy + s * 0.20f);
                c.drawPath(mPath, mP);
            } else if (mKind == KIND_VOLUME) {
                mP.setColor(mColor);
                mP.setStyle(Paint.Style.FILL);
                mPath.reset();
                mPath.moveTo(w * 0.14f, h * 0.38f);
                mPath.lineTo(w * 0.32f, h * 0.38f);
                mPath.lineTo(w * 0.54f, h * 0.20f);
                mPath.lineTo(w * 0.54f, h * 0.80f);
                mPath.lineTo(w * 0.32f, h * 0.62f);
                mPath.lineTo(w * 0.14f, h * 0.62f);
                mPath.close();
                c.drawPath(mPath, mP);

                mP.setStyle(Paint.Style.STROKE);
                mP.setStrokeWidth(Math.max(1.6f, s * 0.10f));
                if (mVolLevel <= 0) {
                    c.drawLine(w * 0.64f, h * 0.36f, w * 0.86f, h * 0.64f, mP);
                    c.drawLine(w * 0.86f, h * 0.36f, w * 0.64f, h * 0.64f, mP);
                } else {
                    mArcRect.set(w * 0.50f, h * 0.32f, w * 0.72f, h * 0.68f);
                    c.drawArc(mArcRect, -50, 100, false, mP);
                    if (mVolLevel >= 2) {
                        mArcRect.set(w * 0.52f, h * 0.18f, w * 0.88f, h * 0.82f);
                        c.drawArc(mArcRect, -52, 104, false, mP);
                    }
                }
            } else if (mKind == KIND_AIRPLANE) {
                mP.setStyle(Paint.Style.STROKE);
                mP.setStrokeWidth(Math.max(1.8f, s * 0.10f));
                c.drawLine(w * 0.15f, cy, w * 0.85f, cy, mP);
                c.drawLine(w * 0.42f, h * 0.16f, w * 0.56f, cy, mP);
                c.drawLine(w * 0.42f, h * 0.84f, w * 0.56f, cy, mP);
                c.drawLine(w * 0.18f, h * 0.34f, w * 0.26f, cy, mP);
                c.drawLine(w * 0.18f, h * 0.66f, w * 0.26f, cy, mP);
            } else if (mKind == KIND_BATTERY_SAVER) {
                mP.setStyle(Paint.Style.STROKE);
                mP.setStrokeWidth(Math.max(1.7f, s * 0.10f));
                mArcRect.set(w * 0.16f, h * 0.26f, w * 0.82f, h * 0.74f);
                c.drawRoundRect(mArcRect, s * 0.1f, s * 0.1f, mP);
                c.drawLine(w * 0.85f, h * 0.40f, w * 0.85f, h * 0.60f, mP);
                c.drawLine(w * 0.38f, h * 0.50f, w * 0.60f, h * 0.50f, mP);
                c.drawLine(w * 0.49f, h * 0.38f, w * 0.49f, h * 0.62f, mP);
            } else if (mKind == KIND_DISPLAY_SWITCH) {
                mP.setStyle(Paint.Style.STROKE);
                mP.setStrokeWidth(Math.max(1.7f, s * 0.10f));
                mArcRect.set(w * 0.14f, h * 0.20f, w * 0.86f, h * 0.68f);
                c.drawRoundRect(mArcRect, s * 0.1f, s * 0.1f, mP);
                c.drawLine(w * 0.34f, h * 0.82f, w * 0.66f, h * 0.82f, mP);
                c.drawLine(cx, h * 0.68f, cx, h * 0.82f, mP);
            } else if (mKind == KIND_TOUCHPAD) {
                mP.setStyle(Paint.Style.STROKE);
                mP.setStrokeWidth(Math.max(1.7f, s * 0.10f));
                mArcRect.set(w * 0.16f, h * 0.18f, w * 0.84f, h * 0.82f);
                c.drawRoundRect(mArcRect, s * 0.12f, s * 0.12f, mP);
                c.drawLine(w * 0.16f, h * 0.64f, w * 0.84f, h * 0.64f, mP);
                c.drawLine(cx, h * 0.64f, cx, h * 0.82f, mP);
            }
            c.restore();
        }
    }

    private static class MiniBatteryView extends View {
        private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private int mPct = 100;
        private boolean mCharging = false;

        MiniBatteryView(Context ctx) {
            super(ctx);
        }

        void setBattery(int pct, boolean charging) {
            if (mPct != pct || mCharging != charging) {
                mPct = pct;
                mCharging = charging;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            float bodyR = w * 0.86f;
            float stroke = Math.max(1.4f, h * 0.11f);
            mP.setStyle(Paint.Style.STROKE);
            mP.setStrokeWidth(stroke);
            mP.setColor(0xFFE2E8F0);
            mRect.set(stroke, stroke, bodyR, h - stroke);
            c.drawRoundRect(mRect, h * 0.18f, h * 0.18f, mP);

            mP.setStyle(Paint.Style.FILL);
            mRect.set(bodyR + stroke * 0.5f, h * 0.30f, w - 1f, h * 0.70f);
            c.drawRoundRect(mRect, 1.5f, 1.5f, mP);

            int fillCol = mCharging ? 0xFF4ADE80 : (mPct <= 20 ? 0xFFF87171 : 0xFFE2E8F0);
            mP.setColor(fillCol);
            float pad = stroke * 1.6f;
            float avail = Math.max(0f, bodyR - pad * 2f);
            mRect.set(pad, pad, pad + avail * (mPct / 100f), h - pad);
            c.drawRoundRect(mRect, h * 0.10f, h * 0.10f, mP);
        }
    }

    private static class CursorView extends View {
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mArrow = new Path();
        private boolean mPressing = false;

        CursorView(Context ctx) {
            super(ctx);
            mFill.setColor(0xFF0F172A);
            mFill.setStyle(Paint.Style.FILL);
            mStroke.setColor(Color.WHITE);
            mStroke.setStyle(Paint.Style.STROKE);
            mStroke.setStrokeWidth(2.6f);
            mStroke.setStrokeJoin(Paint.Join.ROUND);

            mRing.setColor(0x9938BDF8);
            mRing.setStyle(Paint.Style.STROKE);
            mRing.setStrokeWidth(2.5f);

            mArrow.moveTo(6f, 4f);
            mArrow.lineTo(6f, 34f);
            mArrow.lineTo(13.5f, 26.5f);
            mArrow.lineTo(19f, 38f);
            mArrow.lineTo(23.5f, 36f);
            mArrow.lineTo(18f, 24.5f);
            mArrow.lineTo(28f, 24.5f);
            mArrow.close();
        }

        void setPressing(boolean p) {
            if (mPressing != p) {
                mPressing = p;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (mPressing) {
                canvas.drawCircle(6f, 6f, 10f, mRing);
            }
            canvas.drawPath(mArrow, mFill);
            canvas.drawPath(mArrow, mStroke);
        }
    }
}
