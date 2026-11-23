package com.extbridge.helper;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;

public class BridgeProvider extends ContentProvider {
    private static final Binder sProcToken = new Binder();

    @Override
    public boolean onCreate() {
        AdbBootstrap.initialize(getContext());
        return true;
    }

    private boolean isTrustedCaller() {
        int uid = Binder.getCallingUid();
        if (uid == 0 || uid == 1000 || uid == 2000) return true;
        return uid == android.os.Process.myUid();
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!isTrustedCaller()) {
            Bundle denied = new Bundle();
            denied.putBoolean("ok", false);
            return denied;
        }
        // BEGIN DAEMON_STATUS_DIAGNOSTICS
        if ("stopForActivation".equals(method)) {
            Bundle result = new Bundle();
            if (arg == null || !arg.matches("[a-zA-Z0-9_-]{16,80}")) { result.putBoolean("stopped", false); return result; }
            result.putBoolean("stopped", BridgeClient.shutdownDaemon(getContext()));
            return result;
        }
        if ("getNativeAdbInfo".equals(method)) return AdbBootstrap.nativeAdbDiagnostics(getContext());
        if ("getDaemonStatus".equals(method)) {
            BridgeClient.Status status = BridgeClient.getStatus();
            Bundle result = new Bundle();
            result.putBoolean("connected", status.connected);
            result.putInt("daemon_pid", status.daemonPid);
            result.putInt("daemon_uid", status.daemonUid);
            result.putBoolean("handshake_verified", status.handshakeVerified);
            result.putInt("external_input_count", status.extInputCnt);
            result.putInt("inject_status", status.injectStatus);
            result.putInt("external_display_id", status.extId);
            result.putString("activation_token", status.activationToken);
            result.putBoolean("protocol_compatible", status.protocolCompatible);
            result.putBoolean("auto_root_enabled", AdbBootstrap.isAutoRootEnabled(getContext()));
            result.putInt("app_pid", android.os.Process.myPid());
            result.putInt("app_uid", android.os.Process.myUid());
            result.putString("issue", status.connectionIssue);
            AdbBootstrap.diagnostics(result);
            return result;
        }
        // END DAEMON_STATUS_DIAGNOSTICS
        if ("setBinder".equals(method) && extras != null) {
            IBinder b = extras.getBinder("cb");
            if (b != null && b.pingBinder()) {
                BridgeClient.setBinder(b);
                BridgeClient.Status status = BridgeClient.getStatus();
                if (BridgeService.sUserStopping || AdbBootstrap.wasCancelled(status.activationToken)) {
                    BridgeClient.shutdownOwned(status.activationToken);
                    Bundle denied = new Bundle(); denied.putBoolean("ok", false); return denied;
                }
                BridgeService.ensureStarted(getContext());
                BridgeService.refreshFromReceiver(getContext());
                Bundle ok = new Bundle();
                ok.putBoolean("ok", true);
                ok.putBinder("proc", sProcToken);
                return ok;
            }
        }
        return new Bundle();
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] sa, String so) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] sa) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] sa) { return 0; }
}
