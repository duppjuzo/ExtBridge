package com.extbridge.helper;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int[][] RES_PRESETS = {
            {3840, 2160},
            {3440, 1440},
            {2560, 1600},
            {2560, 1440},
            {2560, 1080},
            {1920, 1200},
            {1920, 1080},
            {1600, 900},
            {1280, 720}
    };
    private static final String[] RES_LABELS = {
            "3840×2160\n4K 超采",
            "3440×1440\n21:9 准4K",
            "2560×1600\n2.5K 16:10",
            "2560×1440\n2K 原生★",
            "2560×1080\n21:9 带鱼",
            "1920×1200\n16:10",
            "1920×1080\n1080p 16:9",
            "1600×900\n900p 16:9",
            "1280×720\n720p 省流"
    };

    private static final int[] DPI_PRESETS = {100, 120, 140, 160, 200, 240, 280, 320};
    private static final String[] DPI_LABELS = {"100\n极微PC", "120\n桌面极小", "140\n桌面紧凑", "160\n高密★", "200\n适中", "240\n大屏标准", "280\n偏大", "320\n超大"};
    private static final float[] FPS_PRESETS = {165f, 144f, 120f, 90f, 75f, 60f};
    private static final String PREF_ONBOARDING = "extbridge_onboarding";
    private static final String KEY_STARTUP_PERMS_DONE = "startup_perms_done";
    private static final String KEY_NOTIF_ASKED = "notif_asked";
    private static final String KEY_DEV_GUIDE_DONE = "dev_guide_done";
    private static final int REQ_NOTIF_STARTUP = 3901;
    private static final int REQ_NOTIF_DEV_GATE = 3902;
    private static final int PERM_MODE_NONE = 0;
    private static final int PERM_MODE_STARTUP = 1;
    private static final int PERM_MODE_DEV_GATE = 2;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private int mSelW = 2560;
    private int mSelH = 1440;
    private int mSelDpi = 240;
    private float mSelFps = 60f;
    private int mSelRot = 0;
    private int mSelPhysModeId = -1;
    private boolean mInitedFromDaemon = false;

    private TextView mTvStatusTitle;
    private TextView mTvStatusDetail;
    private TextView mBtnToggleInput;
    private TextView mBtnModeDex;
    private TextView mBtnModeMirror;
    private TextView mBtnFullscreenPolicy;
    private TextView mBtnAppModes;
    private TextView mBtnActivate;
    private TextView mTvSwInfo;
    private TextView mTvPhysNotice;
    private TextView mTvGestureStatus;
    private EditText mEdW;
    private EditText mEdH;
    private EditText mEdDpi;
    private TextView mBtnToggleEdid;
    private LinearLayout mEdidPanel;
    private LinearLayout mPhysModesWrap;
    private boolean mEdidExpanded = false;
    private String mLastModesRaw = "";
    private int mLastPhysW = 0;
    private int mLastPhysH = 0;
    private float mLastPhysFps = 60f;

    private AlertDialog mActivationDialog;
    private AlertDialog mPermDialog;
    private int mPendingPermMode = PERM_MODE_NONE;
    private boolean mStartupPermInProgress = false;
    private boolean mWaitingSettingsReturn = false;
    private boolean mUserSkippedDialog = false;
    private boolean mActWorking = false;
    private boolean mAutoRootTried = false;
    private boolean mHostEdited = false;
    private boolean mPairPortEdited = false;
    private boolean mConnPortEdited = false;
    private EditText mEdPairHost;
    private EditText mEdPairPort;
    private EditText mEdPairCode;
    private EditText mEdConnPort;
    private TextView mTvMdnsHint;
    private TextView mTvActStatus;

    private final List<TextView> mResChips = new ArrayList<>();
    private final List<TextView> mRotChips = new ArrayList<>();
    private final List<TextView> mDpiChips = new ArrayList<>();
    private final List<TextView> mFpsChips = new ArrayList<>();

    private final Runnable mRefreshLoop = new Runnable() {
        @Override
        public void run() {
            BridgeClient.Status st = BridgeClient.getStatus();
            if (!st.connected) {
                BridgeClient.requestBinder(MainActivity.this);
            }
            refreshStatus(false);
            mHandler.postDelayed(this, st.connected ? 2000 : 1200);
        }
    };

    private final Runnable mAutoPromptCheck = () -> {
        if (isFinishing()) return;
        if (mStartupPermInProgress || (mPermDialog != null && mPermDialog.isShowing())) return;
        BridgeClient.Status st = BridgeClient.getStatus();
        if (st.connected) return;
        if (!mAutoRootTried && AdbBootstrap.isAutoRootEnabled(this)) {
            mAutoRootTried = true;
            Toast.makeText(this, "正在尝试自动 Root 激活 ExtBridge 服务...", Toast.LENGTH_SHORT).show();
            AdbBootstrap.rootBootstrapAsync(this, false, new AdbBootstrap.ProgressListener() {
                @Override
                public void onStep(String message) {}

                @Override
                public void onResult(boolean success, String message) {
                    refreshStatus(true);
                    if (!success && !isFinishing() && !mUserSkippedDialog
                            && (mActivationDialog == null || !mActivationDialog.isShowing())) {
                        showActivationDialog();
                    }
                }
            });
            return;
        }
        if (!mUserSkippedDialog && (mActivationDialog == null || !mActivationDialog.isShowing())) {
            showActivationDialog();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (ensureOnDisplay0()) return;
        BridgeService.sUserStopping = false;
        BridgeService.ensureStarted(this);
        buildUi();
        refreshStatus(true);
        if (!getOnboardingPrefs().getBoolean(KEY_STARTUP_PERMS_DONE, false) && !hasAllCoreAppPerms()) {
            mStartupPermInProgress = true;
            mHandler.postDelayed(this::startFirstLaunchPermissions, 220);
        }
    }

    private boolean ensureOnDisplay0() {
        try {
            if (getDisplay() != null && getDisplay().getDisplayId() != 0) {
                Intent i = new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                android.app.ActivityOptions o0 = android.app.ActivityOptions.makeBasic();
                o0.setLaunchDisplayId(0);
                startActivity(i, o0.toBundle());
                finishAndRemoveTask();
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private final Runnable mBinderReadyCb = () -> mHandler.post(() -> {
        if (!isFinishing() && !isDestroyed()) refreshStatus(true);
    });

    @Override
    protected void onResume() {
        super.onResume();
        BridgeClient.setOnBinderReady(mBinderReadyCb);
        if (BridgeClient.getService() == null) BridgeClient.requestBinder(this);
        BridgeService.sUserStopping = false;
        BridgeService.ensureStarted(this);
        if (mWaitingSettingsReturn) {
            mWaitingSettingsReturn = false;
            BridgeService.refreshFromReceiver(this);
            refreshStatus(false);
            if (mPendingPermMode == PERM_MODE_STARTUP) {
                finishOrPromptStartupPerms();
            } else if (mPendingPermMode == PERM_MODE_DEV_GATE) {
                if (hasAllCoreAppPerms()) {
                    completeDevGateAndLaunch();
                } else {
                    showPermissionChecklistDialog(PERM_MODE_DEV_GATE);
                }
            }
        } else {
            refreshStatus(false);
        }
        mHandler.post(mRefreshLoop);
        mHandler.removeCallbacks(mAutoPromptCheck);
        if (!mStartupPermInProgress) {
            mHandler.postDelayed(mAutoPromptCheck, 450);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        BridgeClient.removeOnBinderReady(mBinderReadyCb);
        mHandler.removeCallbacks(mRefreshLoop);
        mHandler.removeCallbacks(mAutoPromptCheck);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mPermDialog != null && mPermDialog.isShowing()) {
            mPermDialog.dismiss();
        }
        if (mActivationDialog != null && mActivationDialog.isShowing()) {
            mActivationDialog.dismiss();
        }
        AdbBootstrap.stopDiscovery();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        BridgeService.refreshFromReceiver(this);
        refreshStatus(false);
        if (requestCode == REQ_NOTIF_STARTUP) {
            finishOrPromptStartupPerms();
        } else if (requestCode == REQ_NOTIF_DEV_GATE) {
            if (hasAllCoreAppPerms()) {
                completeDevGateAndLaunch();
            } else {
                showPermissionChecklistDialog(PERM_MODE_DEV_GATE);
            }
        }
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF0B1120);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 64, 28, 48);
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets sys = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                v.setPadding(Math.max(28, sys.left + 20), Math.max(48, sys.top + 16),
                        Math.max(28, sys.right + 20), Math.max(42, sys.bottom + 24));
                return insets;
            });
        }

        // Header
        String verName = "3.1.0";
        try {
            verName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {}
        TextView appTitle = new TextView(this);
        appTitle.setText("ExtBridge 扩展屏控制台 v" + verName);
        appTitle.setTextColor(Color.WHITE);
        appTitle.setTextSize(22f);
        appTitle.getPaint().setFakeBoldText(true);
        root.addView(appTitle);

        TextView appSub = new TextView(this);
        appSub.setText("USB-C 独立扩展桌面 · 通知栏快切 · 大触摸板与输入法转发 · 通知栏可随时常驻调用");
        appSub.setTextColor(0xFF94A3B8);
        appSub.setTextSize(13f);
        appSub.setPadding(0, 4, 0, 18);
        root.addView(appSub);

        // Card 1: Connection Status + Hero Actions
        LinearLayout statusCard = makeCard();
        mTvStatusTitle = new TextView(this);
        mTvStatusTitle.setTextColor(Color.WHITE);
        mTvStatusTitle.setTextSize(16f);
        mTvStatusTitle.getPaint().setFakeBoldText(true);
        statusCard.addView(mTvStatusTitle);

        mTvStatusDetail = new TextView(this);
        mTvStatusDetail.setTextColor(0xFFCBD5E1);
        mTvStatusDetail.setTextSize(13f);
        mTvStatusDetail.setPadding(0, 6, 0, 14);
        statusCard.addView(mTvStatusDetail);

        List<View> heroBtns = new ArrayList<>();
        mBtnToggleInput = makeActionBtn("⇄ 切换键鼠方向", 0xFF2563EB);
        mBtnToggleInput.setOnClickListener(v -> {
            int m = BridgeClient.toggleInput();
            Toast.makeText(this, m == 1 ? "键鼠已切换至：扩展屏" : "键鼠已切换至：平板内屏", Toast.LENGTH_SHORT).show();
            BridgeService.refreshFromReceiver(this);
            refreshStatus(false);
        });
        heroBtns.add(mBtnToggleInput);

        TextView btnTouchpad = makeActionBtn("🖐 打开大触摸板 + 虚拟键盘", 0xFF0284C7);
        btnTouchpad.setOnClickListener(v -> {
            android.app.ActivityOptions o0 = android.app.ActivityOptions.makeBasic();
            o0.setLaunchDisplayId(0);
            startActivity(new Intent(this, TouchpadActivity.class), o0.toBundle());
        });
        heroBtns.add(btnTouchpad);

        TextView btnLauncher = makeActionBtn("⊞ 扩展屏打开桌面", 0xFF0F766E);
        btnLauncher.setOnClickListener(v -> BridgeClient.relaunchLauncher());
        heroBtns.add(btnLauncher);

        TextView btnRebuild = makeActionBtn("↻ 刷新/重建渲染", 0xFF334155);
        btnRebuild.setOnClickListener(v -> {
            BridgeClient.rebuild();
            Toast.makeText(this, "已发送渲染重建指令", Toast.LENGTH_SHORT).show();
        });
        heroBtns.add(btnRebuild);

        mBtnModeDex = makeActionBtn("🖥 DeX 扩展", 0xFF0F766E);
        mBtnModeDex.setOnClickListener(v -> switchDisplayMode(BridgeClient.MIRROR_MODE_DEX));
        heroBtns.add(mBtnModeDex);

        mBtnModeMirror = makeActionBtn("📺 原生镜像", 0xFF334155);
        mBtnModeMirror.setOnClickListener(v -> switchDisplayMode(BridgeClient.MIRROR_MODE_MIRROR));
        heroBtns.add(mBtnModeMirror);

        mBtnFullscreenPolicy = makeActionBtn("⛶ 桌面锁横屏:开", 0xFF0E7490);
        mBtnFullscreenPolicy.setOnClickListener(v -> {
            int fp = BridgeClient.toggleFullscreenPolicy();
            Toast.makeText(this,
                    fp == 1 ? "已开启：DeX 默认横屏平板大屏桌面" : "已关闭：DeX 桌面默认横屏锁定",
                    Toast.LENGTH_SHORT).show();
            BridgeService.refreshFromReceiver(this);
            refreshStatus(false);
        });
        heroBtns.add(mBtnFullscreenPolicy);

        TextView btnDockAutoHide = makeActionBtn(
                BridgeService.isDockAutoHideEnabled(this) ? "📌 Dock自动隐藏:开" : "📌 Dock自动隐藏:关",
                0xFF334155);
        btnDockAutoHide.setOnClickListener(v -> {
            boolean next = !BridgeService.isDockAutoHideEnabled(this);
            BridgeService.setDockAutoHideEnabled(this, next);
            ((TextView) v).setText(next ? "📌 Dock自动隐藏:开" : "📌 Dock自动隐藏:关");
            Toast.makeText(this,
                    next ? "已开启：进入应用后 Dock 自动隐藏（鼠标移到底边唤醒）"
                         : "已关闭：Dock 栏将始终固定在屏幕底部",
                    Toast.LENGTH_SHORT).show();
        });
        heroBtns.add(btnDockAutoHide);

        mBtnAppModes = makeActionBtn("🧩 分应用显示模式 (微信/夸克/红果…)", 0xFF0284C7);
        mBtnAppModes.setOnClickListener(v -> showAppModesDialog());
        heroBtns.add(mBtnAppModes);

        TextView btnBlackout = makeActionBtn("🌙 一键黑屏 (主屏最低亮度)", 0xFF1E293B);
        btnBlackout.setOnClickListener(v -> BridgeService.toggleBlackoutFromApp(this));
        heroBtns.add(btnBlackout);

        mBtnActivate = makeActionBtn("⚡ 激活后台服务 (Root / 无线 / ADB)", 0xFFD97706);
        mBtnActivate.setOnClickListener(v -> {
            mUserSkippedDialog = false;
            showActivationDialog();
        });
        heroBtns.add(mBtnActivate);

        TextView btnExitAll = makeActionBtn("⏻ 停止服务并退出", 0xFFB91C1C);
        btnExitAll.setOnClickListener(v -> {
            Toast.makeText(this, "正在停止 ExtBridge 守护进程并退出…", Toast.LENGTH_SHORT).show();
            BridgeService.stopAllAndExit(this);
        });
        heroBtns.add(btnExitAll);

        addChipWrap(statusCard, heroBtns);
        root.addView(statusCard);

        // Card 2: Resolution + Custom
        LinearLayout resCard = makeCard();
        addCardHeader(resCard, "桌面渲染分辨率预设 (控制清晰度 / 4K超采 / 降分稳帧与发热)");
        TextView resHint = new TextView(this);
        resHint.setText("说明：此区调节扩展桌面 VirtualDisplay 的真实渲染画布（720p/900p 可降低 GPU 负载稳帧，4K 可超采样锐化），全机型 100% 生效；外接显示器硬件物理信号受系统固件控制。");
        resHint.setTextColor(0xFF94A3B8);
        resHint.setTextSize(12f);
        resHint.setPadding(0, 0, 0, 10);
        resCard.addView(resHint);
        List<View> resViews = new ArrayList<>();
        mResChips.clear();
        for (int i = 0; i < RES_PRESETS.length; i++) {
            final int w = RES_PRESETS[i][0];
            final int h = RES_PRESETS[i][1];
            TextView chip = makeChip(RES_LABELS[i]);
            chip.setOnClickListener(v -> {
                boolean wantPortrait = (mSelRot & 1) == 1;
                mSelW = wantPortrait ? h : w;
                mSelH = wantPortrait ? w : h;
                mSelPhysModeId = -1;
                mEdW.setText(String.valueOf(mSelW));
                mEdH.setText(String.valueOf(mSelH));
                updateSelectionStyles();
            });
            mResChips.add(chip);
            resViews.add(chip);
        }
        addChipWrap(resCard, resViews);

        LinearLayout customRow = new LinearLayout(this);
        customRow.setOrientation(LinearLayout.HORIZONTAL);
        customRow.setGravity(Gravity.CENTER_VERTICAL);
        customRow.setPadding(0, 12, 0, 0);

        TextView customLbl = new TextView(this);
        customLbl.setText("自定义分辨率: ");
        customLbl.setTextColor(0xFF94A3B8);
        customLbl.setTextSize(13f);
        customRow.addView(customLbl);

        mEdW = makeNumBox(String.valueOf(mSelW));
        mEdH = makeNumBox(String.valueOf(mSelH));
        TextView xLbl = new TextView(this);
        xLbl.setText("  ×  ");
        xLbl.setTextColor(Color.WHITE);
        customRow.addView(mEdW);
        customRow.addView(xLbl);
        customRow.addView(mEdH);

        TextView btnSwapWh = makeChip("⇅ 对调宽高");
        btnSwapWh.setTextColor(0xFF7DD3FC);
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swLp.leftMargin = 14;
        btnSwapWh.setOnClickListener(v -> {
            syncCustomWHFields();
            int tmp = mSelW;
            mSelW = mSelH;
            mSelH = tmp;
            mSelPhysModeId = -1;
            mEdW.setText(String.valueOf(mSelW));
            mEdH.setText(String.valueOf(mSelH));
            updateSelectionStyles();
            if (BridgeClient.getService() != null) {
                applyChosenConfig();
            }
        });
        customRow.addView(btnSwapWh, swLp);
        resCard.addView(customRow);

        addCardHeaderTopPad(resCard, "输出桌面旋转方向 (竖屏显示器选 90°/270°；横屏居中看竖版可只点上面「⇅ 对调宽高」)");
        String[] rotLabels = {"0° 正常横屏", "90° 顺时针竖屏", "180° 倒置横屏", "270° 逆时针竖屏"};
        List<View> rotViews = new ArrayList<>();
        mRotChips.clear();
        for (int r = 0; r < 4; r++) {
            final int rotVal = r;
            TextView rChip = makeChip(rotLabels[r]);
            rChip.setOnClickListener(v -> {
                syncCustomWHFields();
                mSelRot = rotVal;
                boolean wantPortrait = (rotVal & 1) == 1;
                if (wantPortrait && mSelW > mSelH) {
                    int tmp = mSelW; mSelW = mSelH; mSelH = tmp;
                } else if (!wantPortrait && mSelH > mSelW) {
                    int tmp = mSelW; mSelW = mSelH; mSelH = tmp;
                }
                mEdW.setText(String.valueOf(mSelW));
                mEdH.setText(String.valueOf(mSelH));
                updateSelectionStyles();
                if (BridgeClient.getService() != null) {
                    applyChosenConfig();
                }
            });
            mRotChips.add(rChip);
            rotViews.add(rChip);
        }
        addChipWrap(resCard, rotViews);
        root.addView(resCard);

        // Card 3: DPI + Refresh Rate
        LinearLayout rateCard = makeCard();
        addCardHeader(rateCard, "界面缩放 DPI (数值越小，外接屏界面/图标越紧凑小巧)");
        List<View> dpiViews = new ArrayList<>();
        mDpiChips.clear();
        for (int i = 0; i < DPI_PRESETS.length; i++) {
            final int dpi = DPI_PRESETS[i];
            TextView chip = makeChip(DPI_LABELS[i]);
            chip.setOnClickListener(v -> {
                mSelDpi = dpi;
                if (mEdDpi != null) mEdDpi.setText(String.valueOf(dpi));
                updateSelectionStyles();
            });
            mDpiChips.add(chip);
            dpiViews.add(chip);
        }
        addChipWrap(rateCard, dpiViews);

        LinearLayout customDpiRow = new LinearLayout(this);
        customDpiRow.setOrientation(LinearLayout.HORIZONTAL);
        customDpiRow.setGravity(Gravity.CENTER_VERTICAL);
        customDpiRow.setPadding(0, 10, 0, 0);

        TextView customDpiLbl = new TextView(this);
        customDpiLbl.setText("自定义 DPI (80~640): ");
        customDpiLbl.setTextColor(0xFF94A3B8);
        customDpiLbl.setTextSize(13f);
        customDpiRow.addView(customDpiLbl);

        mEdDpi = makeNumBox(String.valueOf(mSelDpi));
        customDpiRow.addView(mEdDpi);

        TextView customDpiHint = new TextView(this);
        customDpiHint.setText("  填完点下方「应用」即可生效");
        customDpiHint.setTextColor(0xFF64748B);
        customDpiHint.setTextSize(12f);
        customDpiRow.addView(customDpiHint);
        rateCard.addView(customDpiRow);

        mBtnToggleEdid = new TextView(this);
        mBtnToggleEdid.setText("▸ 高级：外接物理信号与帧率尝试 (受设备固件限制)");
        mBtnToggleEdid.setTextColor(0xFF7DD3FC);
        mBtnToggleEdid.setTextSize(13f);
        mBtnToggleEdid.getPaint().setFakeBoldText(true);
        mBtnToggleEdid.setPadding(0, 16, 0, 6);
        mBtnToggleEdid.setOnClickListener(v -> {
            mEdidExpanded = !mEdidExpanded;
            if (mEdidPanel != null) {
                mEdidPanel.setVisibility(mEdidExpanded ? View.VISIBLE : View.GONE);
            }
            mBtnToggleEdid.setText(mEdidExpanded
                    ? "▾ 高级：收起外接物理信号与帧率尝试"
                    : "▸ 高级：外接物理信号与帧率尝试 (受设备固件限制)");
        });
        rateCard.addView(mBtnToggleEdid);

        mEdidPanel = new LinearLayout(this);
        mEdidPanel.setOrientation(LinearLayout.VERTICAL);
        mEdidPanel.setVisibility(View.GONE);

        TextView edidHint = new TextView(this);
        edidHint.setText("注：Xiaomi HyperOS 等系统会在底层锁死外接屏物理信号与 60Hz 上限；此区仅在允许切换外接模式的机型（或向下限帧省电）上有效，平时无需调整。");
        edidHint.setTextColor(0xFF94A3B8);
        edidHint.setTextSize(12f);
        edidHint.setPadding(0, 0, 0, 10);
        mEdidPanel.addView(edidHint);

        addCardHeader(mEdidPanel, "目标刷新率预设");
        List<View> fpsViews = new ArrayList<>();
        mFpsChips.clear();
        for (float f : FPS_PRESETS) {
            TextView chip = makeChip(Math.round(f) + " Hz");
            chip.setOnClickListener(v -> {
                mSelFps = f;
                mSelPhysModeId = -1;
                updateSelectionStyles();
            });
            mFpsChips.add(chip);
            fpsViews.add(chip);
        }
        addChipWrap(mEdidPanel, fpsViews);

        addCardHeaderTopPad(mEdidPanel, "显示器硬件报告模式 (EDID 实测挡位)");
        mPhysModesWrap = new LinearLayout(this);
        mPhysModesWrap.setOrientation(LinearLayout.VERTICAL);
        mEdidPanel.addView(mPhysModesWrap);
        rateCard.addView(mEdidPanel);

        mTvSwInfo = new TextView(this);
        mTvSwInfo.setTextColor(0xFF94A3B8);
        mTvSwInfo.setTextSize(12.5f);
        mTvSwInfo.setPadding(0, 14, 0, 6);
        rateCard.addView(mTvSwInfo);

        mTvPhysNotice = new TextView(this);
        mTvPhysNotice.setTextColor(0xFFFBBF24);
        mTvPhysNotice.setTextSize(12f);
        mTvPhysNotice.setPadding(0, 0, 0, 8);
        mTvPhysNotice.setVisibility(View.GONE);
        rateCard.addView(mTvPhysNotice);

        TextView btnPhoneTabletPreset = makeActionBtn("📱 手机推荐：一键横屏平板大屏 (1920×1080 @ 240dpi)", 0xFF0369A1);
        btnPhoneTabletPreset.setTextSize(12.5f);
        btnPhoneTabletPreset.setOnClickListener(v -> {
            mSelW = 1920; mSelH = 1080; mSelDpi = 240; mSelFps = 60f; mSelRot = 0; mSelPhysModeId = -1;
            mEdW.setText("1920"); mEdH.setText("1080");
            if (mEdDpi != null) mEdDpi.setText("240");
            updateSelectionStyles();
            applyChosenConfig();
        });
        rateCard.addView(btnPhoneTabletPreset);

        LinearLayout applyRow = new LinearLayout(this);
        applyRow.setOrientation(LinearLayout.HORIZONTAL);
        applyRow.setPadding(0, 16, 0, 0);

        TextView btnApply = makeActionBtn("✓ 应用渲染分辨率与设置", 0xFF16A34A);
        btnApply.setOnClickListener(v -> applyChosenConfig());
        TextView btnReset = makeActionBtn("↺ 恢复推荐默认", 0xFF334155);
        btnReset.setOnClickListener(v -> applyMonitorRecommendedDefault());
        applyRow.addView(btnApply);
        applyRow.addView(btnReset);
        rateCard.addView(applyRow);
        root.addView(rateCard);

        // Card 4: Tablet Gesture Protection & Notification Health
        LinearLayout gestCard = makeCard();
        addCardHeader(gestCard, "平板手势与权限健康检查");
        mTvGestureStatus = new TextView(this);
        mTvGestureStatus.setTextColor(0xFFCBD5E1);
        mTvGestureStatus.setTextSize(13f);
        mTvGestureStatus.setPadding(0, 4, 0, 12);
        gestCard.addView(mTvGestureStatus);

        List<View> permBtns = new ArrayList<>();
        TextView btnA11y = makeActionBtn("♿ 无障碍服务 (必需)", 0xFF059669);
        btnA11y.setOnClickListener(v -> openAccessibilitySettings(PERM_MODE_NONE));
        permBtns.add(btnA11y);

        TextView btnRestricted = makeActionBtn("🔓 允许受限设置", 0xFF334155);
        btnRestricted.setOnClickListener(v -> openAppDetailsForRestricted(PERM_MODE_NONE));
        permBtns.add(btnRestricted);

        TextView btnOverlay = makeActionBtn("悬浮窗权限", 0xFF334155);
        btnOverlay.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        permBtns.add(btnOverlay);

        TextView btnNotif = makeActionBtn("通知栏权限", 0xFF334155);
        btnNotif.setOnClickListener(v -> {
            Intent ni = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(ni);
        });
        permBtns.add(btnNotif);

        addChipWrap(gestCard, permBtns);
        root.addView(gestCard);

        sv.addView(root);
        setContentView(sv);
        updateSelectionStyles();
    }

    private void syncCustomWHFields() {
        try {
            int cw = Integer.parseInt(mEdW.getText().toString().trim());
            int ch = Integer.parseInt(mEdH.getText().toString().trim());
            if (Math.max(cw, ch) >= 640 && Math.max(cw, ch) <= 7680
                    && Math.min(cw, ch) >= 480 && Math.min(cw, ch) <= 4320) {
                if (cw != mSelW || ch != mSelH) {
                    mSelPhysModeId = -1;
                }
                mSelW = cw;
                mSelH = ch;
            }
        } catch (Throwable ignored) {}
        try {
            if (mEdDpi != null) {
                int cd = Integer.parseInt(mEdDpi.getText().toString().trim());
                if (cd >= 80 && cd <= 640) {
                    mSelDpi = cd;
                }
            }
        } catch (Throwable ignored) {}
    }

    private void applyChosenConfig() {
        syncCustomWHFields();
        final int w = mSelW, h = mSelH, dpi = mSelDpi, physId = mSelPhysModeId, rot = mSelRot;
        final float fps = mSelFps;
        new Thread(() -> {
            final BridgeClient.ApplyResult res = BridgeClient.applyConfigDetailed(w, h, dpi, fps, physId, rot);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (!res.ok) {
                    if (mTvPhysNotice != null) mTvPhysNotice.setVisibility(View.GONE);
                    Toast.makeText(this, "应用失败，请确认 ExtBridge 后台服务正在运行", Toast.LENGTH_SHORT).show();
                } else if (res.physResult == -1 && res.physW > 0) {
                    String hint = "ℹ️ 虚拟桌面已切换为 " + w + "×" + h + "；显示器 EDID 不支持 "
                            + Math.round(fps) + "Hz，外接物理输出保持 " + res.physW + "×" + res.physH
                            + "@" + Math.round(res.physFps) + "Hz，已自动缩放适配。";
                    if (mTvPhysNotice != null) {
                        mTvPhysNotice.setText(hint);
                        mTvPhysNotice.setVisibility(View.VISIBLE);
                    }
                    Toast.makeText(this, hint, Toast.LENGTH_LONG).show();
                } else if ((res.physResult == -2 || res.physResult == -3) && res.physW > 0) {
                    String hint = "ℹ️ 虚拟桌面已切换为 " + w + "×" + h + "；当前系统固件锁定外接物理输出为 "
                            + res.physW + "×" + res.physH + "@" + Math.round(res.physFps) + "Hz，已自动通过硬件缩放铺满。";
                    if (mTvPhysNotice != null) {
                        mTvPhysNotice.setText(hint);
                        mTvPhysNotice.setVisibility(View.VISIBLE);
                    }
                    Toast.makeText(this, hint, Toast.LENGTH_LONG).show();
                } else {
                    if (mTvPhysNotice != null) mTvPhysNotice.setVisibility(View.GONE);
                    String physNote = (res.physW > 0)
                            ? (" | 物理:" + res.physW + "×" + res.physH + "@" + Math.round(res.physFps) + "Hz")
                            : "";
                    Toast.makeText(this,
                            "已应用虚拟渲染：" + w + "×" + h + " · " + BridgeClient.rotationLabel(rot)
                                    + " · " + dpi + "dpi" + physNote,
                            Toast.LENGTH_SHORT).show();
                }
                BridgeService.refreshFromReceiver(this);
                refreshStatus(false);
            });
        }).start();
    }

    private void refreshStatus(boolean syncSelection) {
        BridgeClient.Status st = BridgeClient.getStatus();
        if (!st.connected) {
            mTvStatusTitle.setText("● 未检测到 ExtBridge 后台守护进程");
            mTvStatusTitle.setTextColor(0xFFF87171);
            mTvStatusDetail.setText("请通过「Root 一键激活」「本机无线调试」或「电脑 ADB 命令」一键激活，无需手动传输任何脚本。");
            mBtnToggleInput.setText("⇄ 切换键鼠方向");
            updateModeButtonsUi(BridgeClient.MIRROR_MODE_DEX, false);
            if (mBtnFullscreenPolicy != null) mBtnFullscreenPolicy.setText("⛶ 全屏策略");
            if (mBtnActivate != null) mBtnActivate.setVisibility(View.VISIBLE);
        } else {
            mUserSkippedDialog = false;
            if (mBtnActivate != null) mBtnActivate.setVisibility(View.GONE);
            if (mActivationDialog != null && mActivationDialog.isShowing()) {
                mActivationDialog.dismiss();
                AdbBootstrap.stopDiscovery();
                AdbBootstrap.cancelPairingNotification(this);
                Toast.makeText(this, "✅ ExtBridge 服务激活成功，已进入控制台", Toast.LENGTH_SHORT).show();
            }
            if (syncSelection && !mInitedFromDaemon) {
                mInitedFromDaemon = true;
                mSelW = st.virtW;
                mSelH = st.virtH;
                mSelDpi = st.virtDpi;
                mSelFps = st.virtFps;
                mSelRot = st.virtRot;
                mEdW.setText(String.valueOf(mSelW));
                mEdH.setText(String.valueOf(mSelH));
                if (mEdDpi != null) mEdDpi.setText(String.valueOf(mSelDpi));
                updateSelectionStyles();
            } else if (syncSelection || (mEdW != null && !mEdW.hasFocus() && mEdH != null && !mEdH.hasFocus()
                    && (mSelRot != st.virtRot || mSelW != st.virtW || mSelH != st.virtH))) {
                // Keep UI in sync when rotation or resolution is changed from quick flyout panel
                if (st.connected && (mSelRot != st.virtRot)) {
                    mSelW = st.virtW;
                    mSelH = st.virtH;
                    mSelRot = st.virtRot;
                    mEdW.setText(String.valueOf(mSelW));
                    mEdH.setText(String.valueOf(mSelH));
                    if (mEdDpi != null && !mEdDpi.hasFocus()) mEdDpi.setText(String.valueOf(st.virtDpi));
                    updateSelectionStyles();
                }
            }
            if (st.mirrorMode == 1 && st.extId > 0) {
                mTvStatusTitle.setText("● 原生镜像模式 · 物理 Display #" + st.extId);
                mTvStatusTitle.setTextColor(0xFFA78BFA);
                mTvStatusDetail.setText("外接屏正在原生同步手机/平板主屏（独立桌面已暂停）。\n"
                        + "点击上方「🖥 DeX 扩展」或下拉通知栏按钮可随时切回。\n"
                        + "外接物理输出：" + st.physW + "×" + st.physH + " @ " + String.format("%.1f", st.physFps) + "Hz");
            } else if (st.vdId > 0) {
                mTvStatusTitle.setText("● 已连接扩展屏 · 物理 Display #" + st.extId + " -> 扩展 Display #" + st.vdId);
                mTvStatusTitle.setTextColor(0xFF4ADE80);
                String target = st.inputToExt == 1 ? "外接扩展屏 (按切回可在通知栏/此页切平板)" : "平板内屏";
                String injectWarn = st.injectStatus == -1
                        ? ("\n⚠ 输入注入被系统拦截 (INJECT_EVENTS)：" + BridgeClient.getOemInjectHint())
                        : "";
                mTvStatusDetail.setText("输出模式：【" + BridgeClient.mirrorModeLabel(st.mirrorMode) + "】 | 键鼠：【" + target + "】 (外接设备 " + st.extInputCnt + " 个)\n"
                        + "虚拟渲染画布：" + st.virtW + "×" + st.virtH + " (" + BridgeClient.rotationLabel(st.virtRot) + ") @ " + st.virtDpi + "dpi · 目标 " + Math.round(st.virtFps) + "Hz (" + st.renderMode + ") · 锁向:" + (st.fullscreenPolicy == 1 ? "开" : "关") + "\n"
                        + "外接物理输出：" + st.physW + "×" + st.physH + " @ " + String.format("%.1f", st.physFps) + "Hz (系统/EDID协商)"
                        + injectWarn);
            } else {
                mTvStatusTitle.setText("● 后台服务待机中 · 请插入 Type-C 外接显示器");
                mTvStatusTitle.setTextColor(0xFFFBBF24);
                mTvStatusDetail.setText("已保存目标预设：" + st.virtW + "×" + st.virtH + " (" + BridgeClient.rotationLabel(st.virtRot) + ") @ " + st.virtDpi + "dpi · " + Math.round(st.virtFps) + "Hz"
                        + " (模式:" + BridgeClient.mirrorModeLabel(st.mirrorMode) + ")");
            }
            mBtnToggleInput.setText(st.inputToExt == 1 ? "⇄ 键鼠切回平板 (当前:扩展)" : "⇄ 键鼠切回扩展 (当前:平板)");
            updateModeButtonsUi(st.mirrorMode, true);
            if (mBtnFullscreenPolicy != null) {
                mBtnFullscreenPolicy.setText(st.fullscreenPolicy == 1 ? "⛶ 桌面锁横屏:开" : "⛶ 桌面锁横屏:关");
            }
            if (st.physW > 0 && st.physH > 0) {
                mLastPhysW = st.physW;
                mLastPhysH = st.physH;
                mLastPhysFps = st.physFps > 10f ? st.physFps : 60f;
            }
            if (!st.modesRaw.equals(mLastModesRaw)) {
                mLastModesRaw = st.modesRaw;
                rebuildPhysModes(st.modesRaw);
            }
        }

        boolean canOverlay = Settings.canDrawOverlays(this);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        boolean canNotif = Build.VERSION.SDK_INT < 24 || nm.areNotificationsEnabled();
        boolean a11yOn = hasAccessibilityPermission();
        mTvGestureStatus.setText(
                "• 路径1 (原生手势保护): 已自动启用 (无系统装饰占位，平板左右侧滑返回与底部上滑回桌面均可正常使用)\n"
                + "• 无障碍服务 (必需): " + (a11yOn ? "已开启" : "未开启（点击下方前往「已下载的应用 → ExtBridge 平板辅助手势」开启）") + "\n"
                + "• 悬浮窗权限: " + (canOverlay ? "已授权" : "未授权 (点击下方开启以显示鼠标光标与浮动条)") + "   |   "
                + "通知栏开关权限: " + (canNotif ? "已授权" : "未授权"));
    }

    private void rebuildPhysModes(String raw) {
        mPhysModesWrap.removeAllViews();
        if (raw == null || raw.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("暂无外接硬件模式数据 (插屏后自动刷新)");
            tv.setTextColor(0xFF64748B);
            tv.setTextSize(12.5f);
            mPhysModesWrap.addView(tv);
            return;
        }
        List<View> modeBtns = new ArrayList<>();
        for (String item : raw.split(";")) {
            String[] kv = item.split(":");
            if (kv.length != 2) continue;
            try {
                final int modeId = Integer.parseInt(kv[0]);
                final String desc = kv[1];
                TextView chip = makeChip("物理模式 #" + modeId + " · " + desc + "Hz");
                chip.setOnClickListener(v -> {
                    mSelPhysModeId = modeId;
                    try {
                        String[] rf = desc.split("@");
                        String[] wh = rf[0].split("x");
                        int pw = Integer.parseInt(wh[0]);
                        int ph = Integer.parseInt(wh[1]);
                        boolean wantPortrait = (mSelRot & 1) == 1;
                        mSelW = wantPortrait ? ph : pw;
                        mSelH = wantPortrait ? pw : ph;
                        mSelFps = Float.parseFloat(rf[1]);
                        mEdW.setText(String.valueOf(mSelW));
                        mEdH.setText(String.valueOf(mSelH));
                        updateSelectionStyles();
                    } catch (Throwable ignored) {}
                    applyChosenConfig();
                });
                modeBtns.add(chip);
            } catch (Throwable ignored) {}
        }
        addChipWrap(mPhysModesWrap, modeBtns);
    }

    private void applyMonitorRecommendedDefault() {
        int bestModeId = -1;
        int bestW = 0;
        int bestH = 0;
        float bestFps = 60f;

        String raw = mLastModesRaw;
        if (raw != null && !raw.isEmpty()) {
            boolean has50Plus = false;
            for (String item : raw.split(";")) {
                String[] kv = item.split(":");
                if (kv.length != 2) continue;
                try {
                    String[] rf = kv[1].split("@");
                    String[] wh = rf[0].split("x");
                    int pw = Integer.parseInt(wh[0]);
                    int ph = Integer.parseInt(wh[1]);
                    float pf = Float.parseFloat(rf[1]);
                    if (Math.max(pw, ph) >= 640 && Math.min(pw, ph) >= 480 && pf >= 49.5f) {
                        has50Plus = true;
                        break;
                    }
                } catch (Throwable ignored) {}
            }

            long bestArea = -1L;
            float bestFpsScore = -1f;
            for (String item : raw.split(";")) {
                String[] kv = item.split(":");
                if (kv.length != 2) continue;
                try {
                    int modeId = Integer.parseInt(kv[0]);
                    String[] rf = kv[1].split("@");
                    String[] wh = rf[0].split("x");
                    int pw = Integer.parseInt(wh[0]);
                    int ph = Integer.parseInt(wh[1]);
                    float pf = Float.parseFloat(rf[1]);
                    if (Math.max(pw, ph) < 640 || Math.min(pw, ph) < 480) continue;
                    if (has50Plus && pf < 49.5f) continue;
                    long area = (long) pw * (long) ph;
                    float fpsScore = Math.min(pf, 120f);
                    if (area > bestArea || (area == bestArea && fpsScore > bestFpsScore + 0.2f)) {
                        bestArea = area;
                        bestFpsScore = fpsScore;
                        bestModeId = modeId;
                        bestW = pw;
                        bestH = ph;
                        bestFps = pf;
                    }
                } catch (Throwable ignored) {}
            }
        }

        if (bestW <= 0 || bestH <= 0) {
            if (mLastPhysW >= 640 && mLastPhysH >= 480) {
                bestW = mLastPhysW;
                bestH = mLastPhysH;
                bestFps = mLastPhysFps;
            } else {
                bestW = 2560;
                bestH = 1440;
                bestFps = 60f;
            }
        }

        int minEdge = Math.min(bestW, bestH);
        int recDpi = minEdge >= 2000 ? 240 : (minEdge >= 1520 ? 200 : 160);

        mSelW = bestW;
        mSelH = bestH;
        mSelDpi = recDpi;
        mSelFps = bestFps;
        mSelRot = 0;
        mSelPhysModeId = bestModeId;
        mEdW.setText(String.valueOf(bestW));
        mEdH.setText(String.valueOf(bestH));
        if (mEdDpi != null) mEdDpi.setText(String.valueOf(recDpi));
        updateSelectionStyles();
        applyChosenConfig();
    }

    private void updateSelectionStyles() {
        for (int i = 0; i < RES_PRESETS.length && i < mResChips.size(); i++) {
            boolean sel = (RES_PRESETS[i][0] == mSelW && RES_PRESETS[i][1] == mSelH)
                    || (RES_PRESETS[i][0] == mSelH && RES_PRESETS[i][1] == mSelW);
            styleChip(mResChips.get(i), sel);
        }
        for (int i = 0; i < mRotChips.size(); i++) {
            styleChip(mRotChips.get(i), i == mSelRot);
        }
        for (int i = 0; i < DPI_PRESETS.length && i < mDpiChips.size(); i++) {
            styleChip(mDpiChips.get(i), DPI_PRESETS[i] == mSelDpi);
        }
        for (int i = 0; i < FPS_PRESETS.length && i < mFpsChips.size(); i++) {
            styleChip(mFpsChips.get(i), Math.abs(FPS_PRESETS[i] - mSelFps) < 1f);
        }
        updateSwHint();
    }

    private void updateSwHint() {
        if (mTvSwInfo == null) return;
        int cw = mSelW;
        int ch = mSelH;
        try {
            int pw = Integer.parseInt(mEdW.getText().toString().trim());
            int ph = Integer.parseInt(mEdH.getText().toString().trim());
            if (pw > 0 && ph > 0) { cw = pw; ch = ph; }
        } catch (Throwable ignored) {}
        int minPx = Math.min(cw, ch);
        int swDp = Math.round(minPx * 160f / Math.max(80, mSelDpi));
        boolean tabletLayout = swDp >= 600;
        mTvSwInfo.setText("当前等效最小宽度 sw" + swDp + "dp → "
                + (tabletLayout ? "✅ 已达平板大屏阈值 (sw≥600dp，视频软件优先横向大屏)"
                                : "⚠️ 低于平板阈值 (sw<600dp，建议 1920×1080 @ 240dpi 或更低 DPI)"));
        mTvSwInfo.setTextColor(tabletLayout ? 0xFF7DD3FC : 0xFFFBBF24);
    }

    private void styleChip(TextView tv, boolean selected) {
        GradientDrawable bg = (GradientDrawable) tv.getBackground();
        if (selected) {
            bg.setColor(0xFF1D4ED8);
            bg.setStroke(2, 0xFF38BDF8);
            tv.setTextColor(Color.WHITE);
        } else {
            bg.setColor(0xFF1E293B);
            bg.setStroke(1, 0x3364748B);
            tv.setTextColor(0xFFCBD5E1);
        }
    }

    private LinearLayout makeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(22, 18, 22, 20);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF131C2E);
        bg.setCornerRadius(20f);
        bg.setStroke(1, 0x2238BDF8);
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 16;
        card.setLayoutParams(lp);
        return card;
    }

    private void addCardHeader(LinearLayout card, String txt) {
        TextView tv = new TextView(this);
        tv.setText(txt);
        tv.setTextColor(0xFFE2E8F0);
        tv.setTextSize(14.5f);
        tv.getPaint().setFakeBoldText(true);
        tv.setPadding(0, 0, 0, 10);
        card.addView(tv);
    }

    private void addCardHeaderTopPad(LinearLayout card, String txt) {
        TextView tv = new TextView(this);
        tv.setText(txt);
        tv.setTextColor(0xFFE2E8F0);
        tv.setTextSize(14.5f);
        tv.getPaint().setFakeBoldText(true);
        tv.setPadding(0, 16, 0, 10);
        card.addView(tv);
    }

    private TextView makeChip(String txt) {
        TextView tv = new TextView(this);
        tv.setText(txt);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(12.5f);
        tv.setPadding(20, 11, 20, 11);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1E293B);
        bg.setCornerRadius(14f);
        bg.setStroke(1, 0x3364748B);
        tv.setBackground(bg);
        return tv;
    }

    private void switchDisplayMode(int mode) {
        int m = BridgeClient.setMirrorMode(mode);
        Toast.makeText(this, "已切换为【" + BridgeClient.mirrorModeLabel(m >= 0 ? m : mode) + "】", Toast.LENGTH_SHORT).show();
        BridgeService.refreshFromReceiver(this);
        refreshStatus(false);
    }

    private void updateModeButtonsUi(int mirrorMode, boolean connected) {
        setActionBtnBg(mBtnModeDex, (connected && mirrorMode == BridgeClient.MIRROR_MODE_DEX) ? 0xFF0F766E : 0xFF334155);
        setActionBtnBg(mBtnModeMirror, (connected && mirrorMode == BridgeClient.MIRROR_MODE_MIRROR) ? 0xFF7C3AED : 0xFF334155);
    }

    private void setActionBtnBg(TextView tv, int color) {
        if (tv == null) return;
        if (tv.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) tv.getBackground()).setColor(color);
        } else {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(color);
            bg.setCornerRadius(14f);
            tv.setBackground(bg);
        }
    }

    private TextView makeActionBtn(String txt, int color) {
        TextView tv = new TextView(this);
        tv.setText(txt);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(13.5f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(22, 13, 22, 13);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(14f);
        tv.setBackground(bg);
        return tv;
    }

    private EditText makeNumBox(String val) {
        EditText ed = new EditText(this);
        ed.setText(val);
        ed.setTextColor(Color.WHITE);
        ed.setTextSize(14f);
        ed.setInputType(InputType.TYPE_CLASS_NUMBER);
        ed.setGravity(Gravity.CENTER);
        ed.setPadding(16, 8, 16, 8);
        ed.setMinWidth(130);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0F172A);
        bg.setCornerRadius(10f);
        bg.setStroke(1, 0xFF475569);
        ed.setBackground(bg);
        return ed;
    }

    private void addChipWrap(LinearLayout container, List<View> views) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int maxW = Math.max(480, dm.widthPixels - 120);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, 10);
        int curW = 0;

        for (View v : views) {
            v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            int vw = v.getMeasuredWidth() + 14;
            if (curW > 0 && curW + vw > maxW) {
                container.addView(row);
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, 0, 0, 10);
                curW = 0;
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = 10;
            row.addView(v, lp);
            curW += vw;
        }
        if (row.getChildCount() > 0) container.addView(row);
    }

    private void showActivationDialog() {
        if (isFinishing()) return;
        if (mActivationDialog != null && mActivationDialog.isShowing()) return;

        mHostEdited = false;
        mPairPortEdited = false;
        mConnPortEdited = false;

        ScrollView sv = new ScrollView(this);
        GradientDrawable dlgBg = new GradientDrawable();
        dlgBg.setColor(0xFF0F172A);
        dlgBg.setCornerRadius(24f);
        dlgBg.setStroke(2, 0xFF334155);
        sv.setBackground(dlgBg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(34, 30, 34, 28);
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("⚡ 激活 ExtBridge 核心服务");
        title.setTextColor(Color.WHITE);
        title.setTextSize(19f);
        title.getPaint().setFakeBoldText(true);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("未检测到后台守护进程（新安装或重启后需激活一次）。请选择激活方式，检测连接成功后将自动进入应用：");
        sub.setTextColor(0xFF94A3B8);
        sub.setTextSize(12.5f);
        sub.setPadding(0, 8, 0, 18);
        root.addView(sub);

        // Three mode selector buttons
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tabLp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tabLp1.rightMargin = 8;
        LinearLayout.LayoutParams tabLp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tabLp2.rightMargin = 8;
        LinearLayout.LayoutParams tabLp3 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        final TextView btnModeRoot = makeActionBtn("👑 Root 激活\n(免 ADB 权限)", 0xFF1E293B);
        final TextView btnModeWireless = makeActionBtn("📡 无线调试\n(免 Root 免电脑)", 0xFF1E293B);
        final TextView btnModeAdb = makeActionBtn("💻 ADB 命令\n(电脑执行一句)", 0xFF1E293B);
        btnModeRoot.setTextSize(12f);
        btnModeWireless.setTextSize(12f);
        btnModeAdb.setTextSize(12f);
        tabRow.addView(btnModeRoot, tabLp1);
        tabRow.addView(btnModeWireless, tabLp2);
        tabRow.addView(btnModeAdb, tabLp3);
        root.addView(tabRow);

        // Panel 0: Root One-Click
        final LinearLayout rootPane = new LinearLayout(this);
        rootPane.setOrientation(LinearLayout.VERTICAL);
        rootPane.setPadding(0, 18, 0, 0);
        rootPane.setVisibility(View.GONE);

        TextView rootDesc = new TextView(this);
        rootDesc.setText("适合已 Root 设备（Magisk / KernelSU / APatch）。无需开启「开发者选项」或「无线调试」，点击下方按钮授一次权即可一键注册并拉起服务。\n"
                + (AdbBootstrap.hasSuBinaryHint() ? "✓ 检测到系统存在 su 可执行文件" : "ℹ 若使用 KernelSU/APatch 隐藏了 su，仍可直接点击下方按钮请求授权"));
        rootDesc.setTextColor(0xFFCBD5E1);
        rootDesc.setTextSize(12.5f);
        rootDesc.setPadding(0, 0, 0, 12);
        rootPane.addView(rootDesc);

        final TextView btnRootRun = makeActionBtn("👑 一键 Root 注册并激活服务", 0xFF059669);
        rootPane.addView(btnRootRun);

        final TextView btnAutoRootToggle = makeActionBtn(
                AdbBootstrap.isAutoRootEnabled(this) ? "🔄 启动软件时自动 Root 激活：开" : "🔄 启动软件时自动 Root 激活：关",
                0xFF1E293B);
        btnAutoRootToggle.setTextSize(12.5f);
        LinearLayout.LayoutParams artLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        artLp.topMargin = 10;
        btnAutoRootToggle.setOnClickListener(v -> {
            boolean next = !AdbBootstrap.isAutoRootEnabled(this);
            AdbBootstrap.setAutoRootEnabled(this, next);
            btnAutoRootToggle.setText(next ? "🔄 启动软件时自动 Root 激活：开" : "🔄 启动软件时自动 Root 激活：关");
            setTabSelected(btnAutoRootToggle, next);
        });
        setTabSelected(btnAutoRootToggle, AdbBootstrap.isAutoRootEnabled(this));
        rootPane.addView(btnAutoRootToggle, artLp);
        root.addView(rootPane);

        // Panel 1: Wireless Debugging
        final LinearLayout wirelessPane = new LinearLayout(this);
        wirelessPane.setOrientation(LinearLayout.VERTICAL);
        wirelessPane.setPadding(0, 18, 0, 0);
        wirelessPane.setVisibility(View.GONE);

        mTvMdnsHint = new TextView(this);
        mTvMdnsHint.setTextColor(0xFF38BDF8);
        mTvMdnsHint.setTextSize(12f);
        mTvMdnsHint.setPadding(14, 10, 14, 10);
        GradientDrawable mdnsBg = new GradientDrawable();
        mdnsBg.setColor(0xFF1E293B);
        mdnsBg.setCornerRadius(12f);
        mTvMdnsHint.setBackground(mdnsBg);
        wirelessPane.addView(mTvMdnsHint);

        // Step helper buttons
        LinearLayout guideRow = new LinearLayout(this);
        guideRow.setOrientation(LinearLayout.HORIZONTAL);
        guideRow.setPadding(0, 12, 0, 12);
        LinearLayout.LayoutParams gLp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        gLp1.rightMargin = 8;
        LinearLayout.LayoutParams gLp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        TextView btnOpenDev = makeActionBtn("1. 去开启无线调试 + 配对通知", 0xFF0369A1);
        btnOpenDev.setTextSize(12f);
        btnOpenDev.setOnClickListener(v -> openDeveloperOptionsWithGate());
        guideRow.addView(btnOpenDev, gLp1);

        TextView btnPairNotif = makeActionBtn("🔔 发送/刷新通知快捷配对", 0xFF334155);
        btnPairNotif.setTextSize(12f);
        btnPairNotif.setOnClickListener(v -> {
            if (ensureNotifPermAndShowPairNotif()) {
                Toast.makeText(this, "已发送配对通知：在系统配对弹窗上方下拉通知即可直接填6位配对码", Toast.LENGTH_LONG).show();
            }
        });
        guideRow.addView(btnPairNotif, gLp2);
        wirelessPane.addView(guideRow);

        // Input fields: IP + Pair Port + Pair Code + Connect Port
        mEdPairHost = makeDialogInput(AdbBootstrap.getLocalIpv4(), "本机 IP 地址 (如 192.168.1.5)",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        mEdPairPort = makeDialogInput("", "配对端口 (弹窗上的端口，如 36565)", InputType.TYPE_CLASS_NUMBER);
        mEdPairCode = makeDialogInput("", "6 位 WLAN 配对码 (如 551857)", InputType.TYPE_CLASS_NUMBER);
        mEdConnPort = makeDialogInput("", "无线调试主页连接端口 (若已配对直接填，如 37991)", InputType.TYPE_CLASS_NUMBER);

        attachEditFlag(mEdPairHost, () -> mHostEdited = true);
        attachEditFlag(mEdPairPort, () -> mPairPortEdited = true);
        attachEditFlag(mEdConnPort, () -> mConnPortEdited = true);

        addLabeledField(wirelessPane, "IP 地址", mEdPairHost);
        addLabeledField(wirelessPane, "配对端口 & 6位配对码 (新设备配对必填)", mEdPairPort);
        LinearLayout.LayoutParams codeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        codeLp.topMargin = 6;
        wirelessPane.addView(mEdPairCode, codeLp);
        addLabeledField(wirelessPane, "连接端口 (已配对过可直接填此端口跳过配对)", mEdConnPort);

        LinearLayout runRow = new LinearLayout(this);
        runRow.setOrientation(LinearLayout.HORIZONTAL);
        runRow.setPadding(0, 14, 0, 8);
        LinearLayout.LayoutParams rLp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rLp1.rightMargin = 8;
        LinearLayout.LayoutParams rLp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        TextView btnPairRun = makeActionBtn("🔗 配对并激活", 0xFF2563EB);
        TextView btnConnRun = makeActionBtn("⚡ 已配对·直接连接", 0xFF0F766E);
        runRow.addView(btnPairRun, rLp1);
        runRow.addView(btnConnRun, rLp2);
        wirelessPane.addView(runRow);
        root.addView(wirelessPane);

        // Panel 2: PC ADB Command
        final LinearLayout adbPane = new LinearLayout(this);
        adbPane.setOrientation(LinearLayout.VERTICAL);
        adbPane.setPadding(0, 18, 0, 0);
        adbPane.setVisibility(View.GONE);

        TextView adbDesc = new TextView(this);
        adbDesc.setText("在电脑通过 USB 或无线 ADB 连接本机后，复制并执行下方命令（全自动释放内置 extbridge.jar、授权并启动服务）：");
        adbDesc.setTextColor(0xFFCBD5E1);
        adbDesc.setTextSize(12.5f);
        adbDesc.setPadding(0, 0, 0, 10);
        adbPane.addView(adbDesc);

        final String cmdText = AdbBootstrap.buildPcAdbCommand();
        TextView tvCmd = new TextView(this);
        tvCmd.setText(cmdText);
        tvCmd.setTextColor(0xFF86EFAC);
        tvCmd.setTextSize(11f);
        tvCmd.setTypeface(Typeface.MONOSPACE);
        tvCmd.setTextIsSelectable(true);
        tvCmd.setPadding(16, 14, 16, 14);
        GradientDrawable cmdBg = new GradientDrawable();
        cmdBg.setColor(0xFF020617);
        cmdBg.setCornerRadius(12f);
        cmdBg.setStroke(1, 0xFF334155);
        tvCmd.setBackground(cmdBg);
        adbPane.addView(tvCmd);

        TextView btnCopyCmd = makeActionBtn("📋 复制 ADB 激活命令", 0xFF2563EB);
        btnCopyCmd.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("extbridge_adb", cmdText));
            }
            Toast.makeText(this, "已复制命令到剪贴板，请在电脑终端粘贴运行", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        copyLp.topMargin = 12;
        adbPane.addView(btnCopyCmd, copyLp);

        TextView tvWait = new TextView(this);
        tvWait.setText("⏳ 正在自动检测连接状态… 电脑命令执行完成后将自动关闭此窗口并进入应用。");
        tvWait.setTextColor(0xFFFBBF24);
        tvWait.setTextSize(12f);
        tvWait.setPadding(0, 12, 0, 0);
        adbPane.addView(tvWait);
        root.addView(adbPane);

        mTvActStatus = new TextView(this);
        mTvActStatus.setText("提示：Root 用户点「👑 Root 激活」即可一键拉起并自动降权至 shell 身份运行（不做改系统的 Root Hook，定位纯免 Root 桌面）；非 Root 用户用「📡 无线调试」或「💻 ADB 命令」。");
        mTvActStatus.setTextColor(0xFFCBD5E1);
        mTvActStatus.setTextSize(12f);
        mTvActStatus.setPadding(12, 10, 12, 10);
        GradientDrawable stBg = new GradientDrawable();
        stBg.setColor(0xFF020617);
        stBg.setCornerRadius(10f);
        mTvActStatus.setBackground(stBg);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = 14;
        root.addView(mTvActStatus, stLp);

        // Skip button at bottom
        TextView btnSkip = new TextView(this);
        btnSkip.setText("暂不激活，先浏览控制台界面 →");
        btnSkip.setTextColor(0xFF64748B);
        btnSkip.setTextSize(12.5f);
        btnSkip.setGravity(Gravity.CENTER);
        btnSkip.setPadding(12, 20, 12, 6);
        btnSkip.setOnClickListener(v -> {
            mUserSkippedDialog = true;
            if (mActivationDialog != null) mActivationDialog.dismiss();
        });
        root.addView(btnSkip);

        final Runnable selectRoot = () -> {
            rootPane.setVisibility(View.VISIBLE);
            wirelessPane.setVisibility(View.GONE);
            adbPane.setVisibility(View.GONE);
            setTabSelected(btnModeRoot, true);
            setTabSelected(btnModeWireless, false);
            setTabSelected(btnModeAdb, false);
        };
        final Runnable selectWireless = () -> {
            rootPane.setVisibility(View.GONE);
            wirelessPane.setVisibility(View.VISIBLE);
            adbPane.setVisibility(View.GONE);
            setTabSelected(btnModeRoot, false);
            setTabSelected(btnModeWireless, true);
            setTabSelected(btnModeAdb, false);
            AdbBootstrap.startDiscovery(this, this::applyDiscoveryStateToUi);
        };
        final Runnable selectAdb = () -> {
            rootPane.setVisibility(View.GONE);
            wirelessPane.setVisibility(View.GONE);
            adbPane.setVisibility(View.VISIBLE);
            setTabSelected(btnModeRoot, false);
            setTabSelected(btnModeWireless, false);
            setTabSelected(btnModeAdb, true);
        };

        btnModeRoot.setOnClickListener(v -> selectRoot.run());
        btnModeWireless.setOnClickListener(v -> selectWireless.run());
        btnModeAdb.setOnClickListener(v -> selectAdb.run());
        if (AdbBootstrap.hasSuBinaryHint() || AdbBootstrap.isAutoRootEnabled(this)) {
            selectRoot.run();
        }

        btnRootRun.setOnClickListener(v -> {
            if (mActWorking) return;
            mActWorking = true;
            setActStatus("⏳ 正在请求 Root 权限并注册启动服务...", 0xFF38BDF8);
            AdbBootstrap.rootBootstrapAsync(this, true, new AdbBootstrap.ProgressListener() {
                @Override
                public void onStep(String message) {
                    setActStatus("⏳ " + message, 0xFF38BDF8);
                }

                @Override
                public void onResult(boolean success, String message) {
                    mActWorking = false;
                    setActStatus((success ? "✅ " : "❌ ") + message, success ? 0xFF4ADE80 : 0xFFF87171);
                    if (success) {
                        btnAutoRootToggle.setText("🔄 启动软件时自动 Root 激活：开");
                        setTabSelected(btnAutoRootToggle, true);
                    }
                    refreshStatus(true);
                }
            });
        });

        btnPairRun.setOnClickListener(v -> {
            if (mActWorking) return;
            String host = mEdPairHost.getText().toString().trim();
            int pairPort = parseIntStr(mEdPairPort.getText().toString(), 0);
            String code = mEdPairCode.getText().toString().trim();
            int connPort = parseIntStr(mEdConnPort.getText().toString(), 0);
            mActWorking = true;
            setActStatus("⏳ 正在执行配对并启动服务...", 0xFF38BDF8);
            AdbBootstrap.pairAndBootstrapAsync(this, host, pairPort, code, host, connPort,
                    new AdbBootstrap.ProgressListener() {
                        @Override
                        public void onStep(String message) {
                            setActStatus("⏳ " + message, 0xFF38BDF8);
                        }

                        @Override
                        public void onResult(boolean success, String message) {
                            mActWorking = false;
                            setActStatus((success ? "✅ " : "❌ ") + message, success ? 0xFF4ADE80 : 0xFFF87171);
                            refreshStatus(true);
                        }
                    });
        });

        btnConnRun.setOnClickListener(v -> {
            if (mActWorking) return;
            String host = mEdPairHost.getText().toString().trim();
            int connPort = parseIntStr(mEdConnPort.getText().toString(), 0);
            mActWorking = true;
            setActStatus("⏳ 正在连接无线调试并启动服务...", 0xFF38BDF8);
            AdbBootstrap.connectAndBootstrapAsync(this, host, connPort,
                    new AdbBootstrap.ProgressListener() {
                        @Override
                        public void onStep(String message) {
                            setActStatus("⏳ " + message, 0xFF38BDF8);
                        }

                        @Override
                        public void onResult(boolean success, String message) {
                            mActWorking = false;
                            setActStatus((success ? "✅ " : "❌ ") + message, success ? 0xFF4ADE80 : 0xFFF87171);
                            refreshStatus(true);
                        }
                    });
        });

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(sv);
        builder.setCancelable(false);
        mActivationDialog = builder.create();
        if (mActivationDialog.getWindow() != null) {
            mActivationDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        mActivationDialog.show();
        if (mActivationDialog.getWindow() != null) {
            mActivationDialog.getWindow().clearFlags(
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            mActivationDialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private void applyDiscoveryStateToUi(AdbBootstrap.DiscoveryState ds) {
        if (ds == null) return;
        if (mTvMdnsHint != null) {
            String pStr = ds.pairingPort > 0 ? (ds.pairingHost + ":" + ds.pairingPort) : "未开启配对弹窗";
            String cStr = ds.connectPort > 0 ? (ds.connectHost + ":" + ds.connectPort) : "待检测";
            mTvMdnsHint.setText("🔍 mDNS 自动发现：配对=[" + pStr + "] · 连接=[" + cStr + "]");
        }
        if (mEdPairHost != null && !mHostEdited) {
            String bestHost = !ds.pairingHost.isEmpty() ? ds.pairingHost : ds.connectHost;
            if (!bestHost.isEmpty() && !bestHost.equals(mEdPairHost.getText().toString())) {
                mEdPairHost.setText(bestHost);
                mHostEdited = false;
            }
        }
        if (mEdPairPort != null && !mPairPortEdited && ds.pairingPort > 0) {
            mEdPairPort.setText(String.valueOf(ds.pairingPort));
            mPairPortEdited = false;
        }
        if (mEdConnPort != null && !mConnPortEdited && ds.connectPort > 0) {
            mEdConnPort.setText(String.valueOf(ds.connectPort));
            mConnPortEdited = false;
        }
    }

    private void setActStatus(String msg, int color) {
        if (mTvActStatus != null) {
            mTvActStatus.setText(msg);
            mTvActStatus.setTextColor(color);
        }
    }

    private boolean ensureNotifPermAndShowPairNotif() {
        if (!hasNotifPermission()) {
            requestNotifOrOpenSettings(REQ_NOTIF_DEV_GATE);
            return false;
        }
        AdbBootstrap.showPairingNotification(this, null);
        return true;
    }

    private SharedPreferences getOnboardingPrefs() {
        return getSharedPreferences(PREF_ONBOARDING, MODE_PRIVATE);
    }

    private boolean hasNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        return Build.VERSION.SDK_INT < 24 || nm == null || nm.areNotificationsEnabled();
    }

    private boolean hasOverlayPermission() {
        return Settings.canDrawOverlays(this);
    }

    private boolean hasAccessibilityPermission() {
        if (NavAssistService.isRunning()) return true;
        try {
            int enabled = Settings.Secure.getInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            if (enabled != 1) return false;
            String svcs = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (svcs == null) return false;
            String pkg = getPackageName();
            return svcs.contains(pkg + "/.NavAssistService")
                    || svcs.contains(pkg + "/" + NavAssistService.class.getName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean hasAllCoreAppPerms() {
        return hasNotifPermission() && hasOverlayPermission() && hasAccessibilityPermission();
    }

    private void requestNotifOrOpenSettings(int reqCode) {
        mPendingPermMode = (reqCode == REQ_NOTIF_DEV_GATE) ? PERM_MODE_DEV_GATE : PERM_MODE_STARTUP;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            SharedPreferences sp = getOnboardingPrefs();
            boolean asked = sp.getBoolean(KEY_NOTIF_ASKED, false);
            boolean canShowPopup = !asked || shouldShowRequestPermissionRationale("android.permission.POST_NOTIFICATIONS");
            sp.edit().putBoolean(KEY_NOTIF_ASKED, true).apply();
            if (canShowPopup) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, reqCode);
                return;
            }
        }
        mWaitingSettingsReturn = true;
        Toast.makeText(this, "请在系统设置中打开「允许通知」后返回本应用", Toast.LENGTH_LONG).show();
        try {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable ignored) {}
        }
    }

    private void openSettingsWithPermReturn(int mode, String toastMsg, Intent primaryIntent) {
        if (mode != PERM_MODE_NONE) {
            mPendingPermMode = mode;
            mWaitingSettingsReturn = true;
        }
        Toast.makeText(this, toastMsg, Toast.LENGTH_LONG).show();
        try {
            startActivity(primaryIntent);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable ignored) {}
        }
    }

    private void openOverlayPermissionSettings(int mode) {
        openSettingsWithPermReturn(mode,
                "请开启「显示在其他应用上层 / 悬浮窗」权限后返回",
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
    }

    private void openAccessibilitySettings(int mode) {
        openSettingsWithPermReturn(mode,
                "请在「无障碍 → 已下载的应用」中打开「ExtBridge 平板辅助手势」后返回",
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void openAppDetailsForRestricted(int mode) {
        openSettingsWithPermReturn(mode,
                "若无障碍开关为灰色：请点右上角「⋮」→「允许受限设置」后再开无障碍",
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }

    private void startFirstLaunchPermissions() {
        if (isFinishing()) return;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
                && !getOnboardingPrefs().getBoolean(KEY_NOTIF_ASKED, false)) {
            requestNotifOrOpenSettings(REQ_NOTIF_STARTUP);
            return;
        }
        finishOrPromptStartupPerms();
    }

    private void finishOrPromptStartupPerms() {
        if (isFinishing()) return;
        if (hasAllCoreAppPerms()) {
            completeStartupPerms(true);
        } else {
            showPermissionChecklistDialog(PERM_MODE_STARTUP);
        }
    }

    private void completeStartupPerms(boolean allGranted) {
        mStartupPermInProgress = false;
        mPendingPermMode = PERM_MODE_NONE;
        getOnboardingPrefs().edit().putBoolean(KEY_STARTUP_PERMS_DONE, true).apply();
        if (mPermDialog != null && mPermDialog.isShowing()) {
            mPermDialog.dismiss();
        }
        if (allGranted) {
            BridgeService.refreshFromReceiver(this);
            Toast.makeText(this, "✅ 所需应用权限已全部就绪", Toast.LENGTH_SHORT).show();
        }
        refreshStatus(false);
        mHandler.removeCallbacks(mAutoPromptCheck);
        mHandler.postDelayed(mAutoPromptCheck, 200);
    }

    private void openDeveloperOptionsWithGate() {
        if (!hasAllCoreAppPerms()) {
            showPermissionChecklistDialog(PERM_MODE_DEV_GATE);
            return;
        }
        showDevGuideOrLaunchNow(false);
    }

    private void completeDevGateAndLaunch() {
        mPendingPermMode = PERM_MODE_NONE;
        if (mPermDialog != null && mPermDialog.isShowing()) {
            mPermDialog.dismiss();
        }
        Toast.makeText(this, "✅ 权限已补齐，正在发送配对通知", Toast.LENGTH_SHORT).show();
        showDevGuideOrLaunchNow(false);
    }

    private void showDevGuideOrLaunchNow(boolean forceDirect) {
        SharedPreferences sp = getOnboardingPrefs();
        if (!forceDirect && !sp.getBoolean(KEY_DEV_GUIDE_DONE, false)) {
            sp.edit().putBoolean(KEY_DEV_GUIDE_DONE, true).apply();
            showDevOptionsChecklistDialog();
            return;
        }
        launchDeveloperOptionsNow();
    }

    private void launchDeveloperOptionsNow() {
        AdbBootstrap.showPairingNotification(this, null);
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Throwable ignored) {}
        }
    }

    private void showPermissionChecklistDialog(final int mode) {
        if (isFinishing()) return;
        if (mPermDialog != null && mPermDialog.isShowing()) {
            mPermDialog.dismiss();
        }
        mPendingPermMode = mode;

        final boolean notifOk = hasNotifPermission();
        final boolean overlayOk = hasOverlayPermission();
        final boolean a11yOk = hasAccessibilityPermission();
        final boolean isDevGate = (mode == PERM_MODE_DEV_GATE);

        ScrollView sv = new ScrollView(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0F172A);
        bg.setCornerRadius(22f);
        bg.setStroke(2, isDevGate ? 0xFFF59E0B : 0xFF38BDF8);
        sv.setBackground(bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(34, 30, 34, 26);
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText(isDevGate ? "⚠ 开启开发者选项前 · 请先补齐必需权限" : "🔐 首次启动 · 申请必需运行权限");
        title.setTextColor(isDevGate ? 0xFFFBBF24 : Color.WHITE);
        title.setTextSize(18f);
        title.getPaint().setFakeBoldText(true);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText(isDevGate
                ? "检测到仍有必需权限未授予（含无障碍 / 悬浮窗 / 通知）。请先点击下方完成授权后再进入开发者选项："
                : "为确保外接扩展屏高级浮层、全局手势与设置穿透、鼠标准星及无线配对通知正常使用，请完成以下 3 项必需授权：");
        sub.setTextColor(0xFFCBD5E1);
        sub.setTextSize(12.5f);
        sub.setPadding(0, 8, 0, 14);
        root.addView(sub);

        TextView statusBox = new TextView(this);
        statusBox.setText(
                (notifOk ? "✅ 通知权限：已允许（可弹出常驻栏与快捷配对输入框）\n"
                        : "❌ 通知权限：未开启（无线配对弹窗时顶部无法填配对码）\n")
                + (overlayOk ? "✅ 悬浮窗权限：已允许（可显示外接屏鼠标与浮动控制条）\n"
                        : "❌ 悬浮窗权限：未开启（无法显示鼠标光标与控制悬浮条）\n")
                + (a11yOk ? "✅ 无障碍权限：已开启（外接屏高级浮层、手势与系统设置穿透正常）"
                        : "❌ 无障碍权限（必需）：未开启（外接屏浮层、返回桌面手势及直接打开设置依赖此权限）"));
        statusBox.setTextColor(0xFFE2E8F0);
        statusBox.setTextSize(12.5f);
        statusBox.setPadding(16, 14, 16, 14);
        GradientDrawable sbBg = new GradientDrawable();
        sbBg.setColor(0xFF020617);
        sbBg.setCornerRadius(12f);
        sbBg.setStroke(1, 0xFF334155);
        statusBox.setBackground(sbBg);
        root.addView(statusBox);

        if (!notifOk) {
            TextView btnNotif = makeActionBtn("🔔 授予 / 开启通知权限", 0xFF2563EB);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = 14;
            btnNotif.setOnClickListener(v ->
                    requestNotifOrOpenSettings(isDevGate ? REQ_NOTIF_DEV_GATE : REQ_NOTIF_STARTUP));
            root.addView(btnNotif, lp);
        }

        if (!overlayOk) {
            TextView btnOverlay = makeActionBtn("🪟 去开启悬浮窗权限", 0xFF0284C7);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = 10;
            btnOverlay.setOnClickListener(v -> openOverlayPermissionSettings(mode));
            root.addView(btnOverlay, lp);
        }

        if (!a11yOk) {
            TextView btnA11y = makeActionBtn("♿ 去开启无障碍权限（ExtBridge 平板辅助手势）", 0xFF059669);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = 10;
            btnA11y.setOnClickListener(v -> openAccessibilitySettings(mode));
            root.addView(btnA11y, lp);

            TextView btnRestricted = makeActionBtn("🔓 无障碍开关呈灰色（受限设置）？点此进应用详情允许", 0xFF334155);
            btnRestricted.setTextSize(11.5f);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = 8;
            btnRestricted.setOnClickListener(v -> openAppDetailsForRestricted(mode));
            root.addView(btnRestricted, rlp);
        }

        TextView tipBox = new TextView(this);
        tipBox.setText("💡 提示：若在系统「无障碍 → 已下载的应用」点击 ExtBridge 时弹出「受限设置」，请点上方按钮进应用详情右上角「⋮ → 允许受限设置」后再开启（ADB 激活后亦会自动解锁受限设置）；进入开发者选项后请一并开启「无线调试」与「USB 调试（安全设置）」。");
        tipBox.setTextColor(0xFF94A3B8);
        tipBox.setTextSize(11.5f);
        tipBox.setPadding(0, 14, 0, 4);
        root.addView(tipBox);

        TextView btnSkip = new TextView(this);
        btnSkip.setText(isDevGate ? "暂不补齐，仍前往开发者选项 →" : "先跳过（若未激活，去开开发者选项时会再次提醒） →");
        btnSkip.setTextColor(0xFF64748B);
        btnSkip.setTextSize(12f);
        btnSkip.setGravity(Gravity.CENTER);
        btnSkip.setPadding(12, 18, 12, 4);
        btnSkip.setOnClickListener(v -> {
            if (isDevGate) {
                mPendingPermMode = PERM_MODE_NONE;
                if (mPermDialog != null) mPermDialog.dismiss();
                showDevGuideOrLaunchNow(true);
            } else {
                completeStartupPerms(false);
            }
        });
        root.addView(btnSkip);

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setView(sv);
        b.setCancelable(false);
        mPermDialog = b.create();
        if (mPermDialog.getWindow() != null) {
            mPermDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        mPermDialog.show();
    }

    private void showDevOptionsChecklistDialog() {
        if (isFinishing()) return;
        if (mPermDialog != null && mPermDialog.isShowing()) {
            mPermDialog.dismiss();
        }
        ScrollView sv = new ScrollView(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0F172A);
        bg.setCornerRadius(22f);
        bg.setStroke(2, 0xFF38BDF8);
        sv.setBackground(bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(34, 30, 34, 26);
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("🛠 首次前往「开发者选项」提醒");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18f);
        title.getPaint().setFakeBoldText(true);
        root.addView(title);

        TextView body = new TextView(this);
        body.setText("已发送「无线调试快捷配对」通知到顶部状态栏。进入「开发者选项」后请开启以下两项：\n\n"
                + "1. 「无线调试」：点入「使用配对码配对设备」，直接在顶部通知输入 6 位配对码即可完成配对；\n"
                + "2. 「USB 调试（安全设置）/ 允许模拟点击」（小米/HyperOS 必看）：不开启时外接屏触摸板/鼠标可能只见点击动效却点不动软件。");
        body.setTextColor(0xFFCBD5E1);
        body.setTextSize(13f);
        body.setPadding(0, 12, 0, 18);
        root.addView(body);

        TextView btnGo = makeActionBtn("我已知晓，立即打开开发者选项 →", 0xFF0284C7);
        btnGo.setOnClickListener(v -> {
            if (mPermDialog != null) mPermDialog.dismiss();
            launchDeveloperOptionsNow();
        });
        root.addView(btnGo);

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setView(sv);
        b.setCancelable(true);
        mPermDialog = b.create();
        if (mPermDialog.getWindow() != null) {
            mPermDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        mPermDialog.show();
    }

    private void setTabSelected(TextView tv, boolean sel) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(sel ? 0xFF1D4ED8 : 0xFF1E293B);
        bg.setCornerRadius(14f);
        bg.setStroke(2, sel ? 0xFF60A5FA : 0x4464748B);
        tv.setBackground(bg);
    }

    private EditText makeDialogInput(String val, String hint, int inputType) {
        EditText ed = new EditText(this);
        ed.setText(val);
        ed.setHint(hint);
        ed.setHintTextColor(0xFF64748B);
        ed.setTextColor(Color.WHITE);
        ed.setTextSize(13.5f);
        ed.setInputType(inputType);
        ed.setSingleLine(true);
        ed.setPadding(18, 14, 18, 14);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF020617);
        bg.setCornerRadius(10f);
        bg.setStroke(1, 0xFF475569);
        ed.setBackground(bg);
        return ed;
    }

    private void addLabeledField(LinearLayout parent, String label, View field) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFF94A3B8);
        tv.setTextSize(11.5f);
        tv.setPadding(2, 10, 0, 4);
        parent.addView(tv);
        parent.addView(field);
    }

    private void attachEditFlag(EditText ed, final Runnable onUserEdit) {
        ed.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (ed.hasFocus()) onUserEdit.run();
            }
        });
    }

    private static final class AppModeRow {
        String label;
        String pkg;
        int mode;
    }

    private void showAppModesDialog() {
        if (BridgeClient.getService() == null) {
            Toast.makeText(this, "请先激活后台服务再配置分应用显示模式", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 28, 36, 24);
        root.setBackgroundColor(0xFF0F172A);

        TextView title = new TextView(this);
        title.setText("🧩 分应用显示模式管理");
        title.setTextColor(0xFFF8FAFC);
        title.setTextSize(17f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("只有设为非「跟随系统默认」的应用才会注入兼容或全屏参数，微信、夸克、拼多多等建议保持「跟随系统默认」即可恢复正常。");
        desc.setTextColor(0xFF94A3B8);
        desc.setTextSize(12f);
        desc.setPadding(0, 6, 0, 12);
        root.addView(desc);

        EditText edFilter = makeDialogInput("", "搜索应用名或包名（如：微信 / 夸克 / 红果 / pinduoduo）", InputType.TYPE_CLASS_TEXT);
        root.addView(edFilter);

        LinearLayout listWrap = new LinearLayout(this);
        listWrap.setOrientation(LinearLayout.VERTICAL);
        listWrap.setPadding(0, 8, 0, 4);

        TextView loading = new TextView(this);
        loading.setText("正在读取已安装应用与当前兼容配置...");
        loading.setTextColor(0xFF94A3B8);
        loading.setTextSize(12.5f);
        loading.setPadding(8, 16, 8, 16);
        listWrap.addView(loading);

        ScrollView sv = new ScrollView(this);
        sv.addView(listWrap);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(420, (int) (dm.heightPixels * 0.48f)));
        svLp.topMargin = 10;
        root.addView(sv, svLp);

        b.setView(root);
        b.setNeutralButton("↺ 全部重置为默认", null);
        b.setNegativeButton("关闭", null);
        AlertDialog dlg = b.create();
        dlg.show();

        final List<AppModeRow> allRows = new ArrayList<>();
        final Runnable refreshList = () -> rebuildAppModeRows(listWrap, allRows, edFilter.getText().toString(), dlg);

        edFilter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                refreshList.run();
            }
        });

        View resetBtn = dlg.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (resetBtn != null) {
            resetBtn.setOnClickListener(v -> new Thread(() -> {
                BridgeClient.resetAllAppModes();
                Map<String, Integer> latest = BridgeClient.getAllAppModes();
                runOnUiThread(() -> {
                    for (AppModeRow r : allRows) {
                        Integer mv = latest.get(r.pkg);
                        r.mode = mv != null ? mv : 0;
                    }
                    refreshList.run();
                    BridgeService.refreshFromReceiver(this);
                    Toast.makeText(this, "已清空全部自定义兼容覆盖并恢复系统默认", Toast.LENGTH_SHORT).show();
                });
            }).start());
        }

        new Thread(() -> {
            Map<String, Integer> modes = BridgeClient.getAllAppModes();
            PackageManager pm = getPackageManager();
            Intent q = new Intent(Intent.ACTION_MAIN, null);
            q.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(q, 0);
            Map<String, AppModeRow> dedup = new HashMap<>();
            for (ResolveInfo ri : ris) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (getPackageName().equals(pkg) || dedup.containsKey(pkg)) continue;
                AppModeRow row = new AppModeRow();
                row.pkg = pkg;
                row.label = String.valueOf(ri.loadLabel(pm));
                Integer mv = modes.get(pkg);
                row.mode = mv != null ? mv : 0;
                dedup.put(pkg, row);
            }
            List<AppModeRow> loaded = new ArrayList<>(dedup.values());
            Collections.sort(loaded, (a, c) -> {
                int ca = a.mode > 0 ? 0 : 1;
                int cb = c.mode > 0 ? 0 : 1;
                if (ca != cb) return ca - cb;
                return a.label.compareToIgnoreCase(c.label);
            });
            runOnUiThread(() -> {
                if (!dlg.isShowing()) return;
                allRows.clear();
                allRows.addAll(loaded);
                refreshList.run();
            });
        }).start();
    }

    private void rebuildAppModeRows(LinearLayout container, List<AppModeRow> allRows, String query, AlertDialog parentDlg) {
        container.removeAllViews();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (AppModeRow r : allRows) {
            if (!q.isEmpty()) {
                boolean match = r.label.toLowerCase(Locale.ROOT).contains(q)
                        || r.pkg.toLowerCase(Locale.ROOT).contains(q);
                if (!match) continue;
            }
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(20, 14, 20, 14);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(r.mode > 0 ? 0xFF172554 : 0xFF1E293B);
            bg.setCornerRadius(14f);
            bg.setStroke(1, r.mode > 0 ? 0xFF38BDF8 : 0xFF334155);
            card.setBackground(bg);

            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            TextView tvName = new TextView(this);
            tvName.setText(r.label);
            tvName.setTextColor(0xFFF1F5F9);
            tvName.setTextSize(14f);
            tvName.setTypeface(Typeface.DEFAULT_BOLD);
            left.addView(tvName);

            TextView tvPkg = new TextView(this);
            tvPkg.setText(r.pkg);
            tvPkg.setTextColor(0xFF94A3B8);
            tvPkg.setTextSize(11f);
            left.addView(tvPkg);
            card.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView pill = new TextView(this);
            pill.setText(BridgeClient.appModeLabel(r.mode));
            pill.setTextSize(11.5f);
            pill.setPadding(16, 8, 16, 8);
            GradientDrawable pBg = new GradientDrawable();
            int pColor = r.mode == BridgeClient.APP_MODE_LAND_FULL ? 0xFF0284C7
                    : (r.mode == BridgeClient.APP_MODE_PORT_COMPAT ? 0xFFD97706 : 0xFF334155);
            pBg.setColor(pColor);
            pBg.setCornerRadius(12f);
            pill.setBackground(pBg);
            pill.setTextColor(Color.WHITE);
            card.addView(pill);

            card.setOnClickListener(v -> showSingleAppModePicker(r, () -> {
                if (parentDlg.isShowing()) {
                    rebuildAppModeRows(container, allRows, query, parentDlg);
                }
            }));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = 10;
            container.addView(card, lp);
            shown++;
        }
        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText("未找到匹配的应用");
            empty.setTextColor(0xFF94A3B8);
            empty.setPadding(10, 20, 10, 20);
            container.addView(empty);
        }
    }

    private void showSingleAppModePicker(AppModeRow row, Runnable onUpdated) {
        String[] choices = {
                (row.mode == 0 ? "● " : "") + "跟随系统默认（不注入兼容参数，推荐微信/夸克/拼多多）",
                (row.mode == 1 ? "● " : "") + "强制横屏全屏（适合红果短剧等无法横屏的视频 App）",
                (row.mode == 2 ? "● " : "") + "竖屏居中兼容（保持手机竖屏比例不拉伸变形）"
        };
        new AlertDialog.Builder(this)
                .setTitle(row.label + "\n" + row.pkg)
                .setItems(choices, (d, which) -> {
                    Toast.makeText(this, "正在切换并重启「" + row.label + "」...", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        BridgeClient.setAppMode(row.pkg, which, true);
                        row.mode = which;
                        runOnUiThread(() -> {
                            BridgeService.refreshFromReceiver(this);
                            onUpdated.run();
                        });
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int parseIntStr(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return def; }
    }
}
