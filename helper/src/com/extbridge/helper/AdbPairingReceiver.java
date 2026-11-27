package com.extbridge.helper;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Handles inline 6-digit Wireless Debugging pairing code input from the notification shade,
 * allowing users to pair without dismissing Android's pairing code popup.
 */
public class AdbPairingReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (AdbBootstrap.ACTION_STOP_PAIR_NOTIF.equals(action)) {
            AdbBootstrap.cancelNotificationActivation();
            AdbBootstrap.cancelPairingNotification(context);
            return;
        }
        if (!AdbBootstrap.ACTION_PAIR_REPLY.equals(action)) return;

        Bundle input = RemoteInput.getResultsFromIntent(intent);
        if (input == null) return;
        CharSequence raw = input.getCharSequence(AdbBootstrap.KEY_PAIR_INPUT);
        String text = raw == null ? "" : raw.toString().trim();
        if (text.isEmpty()) {
            AdbBootstrap.showPairingNotification(context, "未输入配对码，请重新输入 6 位数字");
            return;
        }

        final PendingResult pr = goAsync();
        final Context appCtx = context.getApplicationContext();
        BridgeService.ensureStarted(appCtx);
        AdbBootstrap.startDiscovery(appCtx, null);
        AdbBootstrap.showPairingNotification(appCtx, "正在配对并准备激活…");
        AdbBootstrap.pairFromNotificationAsync(appCtx, text, new AdbBootstrap.ProgressListener() {
            @Override
            public void onStep(String message) {
                AdbBootstrap.showPairingNotification(appCtx, message);
            }

            @Override
            public void onResult(boolean success, String message) {
                AdbBootstrap.showPairingNotification(appCtx, message);
            }
            @Override
            public void onActivationResult(ActivationFlow.Result result) {
                // The staged flow has verified this request's token and PID, not an unrelated old service.
                if (result.activated()) {
                    BridgeClient.Status status = BridgeClient.getStatus();
                    ActivationFlow.Probe probe = new ActivationFlow.Probe(status.connected, status.handshakeVerified,
                            status.protocolCompatible, status.daemonPid, status.daemonUid, status.activationToken, status.connectionIssue);
                    if (!ActivationFlow.owns(probe, result.token, result.pid)) {
                        AdbBootstrap.showPairingNotification(appCtx, "服务状态已改变，请重新检查");
                        return;
                    }
                    AdbBootstrap.cancelPairingNotification(appCtx);
                    Toast.makeText(appCtx, "本次激活成功", Toast.LENGTH_LONG).show();
                } else AdbBootstrap.showPairingNotification(appCtx, result.message);
            }
        });
        // The foreground service owns the long operation; never retain a broadcast for 60+ seconds.
        pr.finish();
    }
}
