package com.extbridge.helper;

/** Fail-closed deployment shared by Root, native ADB and copied computer commands. */
public final class DaemonLaunchScript {
    private DaemonLaunchScript() {}
    public static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    public static String build(String token, String stagedJar, String cancelFile, boolean root) {
        if (!token.matches("[a-zA-Z0-9_-]{16,80}")) throw new IllegalArgumentException("Invalid activation token");
        String tmp = "/data/local/tmp/extbridge." + token + ".new.jar";
        String guard = cancelFile == null ? "" : "if [ -e " + quote(cancelFile) + " ]; then exit 125; fi; ";
        String extraction = stagedJar == null
                ? "APK=$(pm path com.extbridge.helper | head -n1 | cut -d: -f2); test -n \"$APK\"; unzip -p \"$APK\" assets/extbridge.jar > \"$TMP\"; "
                : "cp " + quote(stagedJar) + " \"$TMP\"; ";
        return "set -e; " + (root ? "test \"$(id -u)\" = 0 || { echo NO_ROOT; exit 12; }; " : "")
                + "TOKEN=" + quote(token) + "; TMP=" + quote(tmp) + "; trap 'rm -f \"$TMP\"' EXIT; " + guard
                + extraction + "test -s \"$TMP\"; unzip -p \"$TMP\" classes.dex >/dev/null; chmod 644 \"$TMP\"; "
                + "VERIFY=$(CLASSPATH=\"$TMP\" timeout 5 app_process /data/local/tmp com.extbridge.daemon.ExtBridge --verify); "
                + "test \"$VERIFY\" = 'EXTBRIDGE_JAR_OK:2'; " + guard
                + "content call --uri content://com.extbridge.helper.provider --method stopForActivation --arg \"$TOKEN\" >/dev/null 2>&1 || true; "
                + "am broadcast -a com.extbridge.daemon.SHUTDOWN -p com.android.shell >/dev/null 2>&1 || true; "
                + "sleep 0.4; pkill -f '^app_process /data/local/tmp com.extbridge.daemon.ExtBridge([[:space:]]|$)' 2>/dev/null || true; "
                + "sleep 0.2; if pgrep -f '^app_process /data/local/tmp com.extbridge.daemon.ExtBridge([[:space:]]|$)' >/dev/null; then echo OLD_DAEMON_NOT_STOPPED; exit 23; fi; "
                + guard + "mv -f \"$TMP\" /data/local/tmp/extbridge.jar; "
                + (root ? "chown 2000:2000 /data/local/tmp/extbridge.jar /data/local/tmp/extbridge.conf /data/local/tmp/extbridge_apps.conf /data/local/tmp/extbridge.log 2>/dev/null || true; " : "")
                + "appops set com.extbridge.helper SYSTEM_ALERT_WINDOW allow 2>/dev/null || true; "
                + "appops set com.extbridge.helper RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true; "
                + "appops set com.extbridge.helper ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null || true; "
                + "pm grant com.extbridge.helper android.permission.POST_NOTIFICATIONS 2>/dev/null || true; " + guard
                + "CLASSPATH=/data/local/tmp/extbridge.jar nohup app_process /data/local/tmp com.extbridge.daemon.ExtBridge --activation-token \"$TOKEN\""
                + (root && cancelFile != null ? " --cancel-file " + quote(cancelFile) : "")
                + " </dev/null >/data/local/tmp/extbridge.log 2>&1 & PID=$!; sleep 0.4; "
                + (cancelFile == null ? "" : "if [ -e " + quote(cancelFile) + " ]; then kill \"$PID\" 2>/dev/null || true; exit 125; fi; ")
                + "kill -0 \"$PID\"; echo \"EXTBRIDGE_LAUNCH:$TOKEN:$PID\"";
    }
    public static String pcCommand() {
        // Generate the token on the target; building/copying the command performs no activation.
        String script = build("EXTERNAL_TOKEN_PLACEHOLDER", null, null, false)
                .replace("TOKEN='EXTERNAL_TOKEN_PLACEHOLDER';", "TOKEN=$(cat /proc/sys/kernel/random/uuid | tr -d '-');")
                .replace("TMP='/data/local/tmp/extbridge.EXTERNAL_TOKEN_PLACEHOLDER.new.jar';", "TMP=\"/data/local/tmp/extbridge.$TOKEN.new.jar\";");
        script += "; END=$(( $(cut -d. -f1 /proc/uptime) + 9 )); "
                + "while [ $(cut -d. -f1 /proc/uptime) -lt \"$END\" ]; do "
                + "STATE=$(content call --uri content://com.extbridge.helper.provider --method getDaemonStatus 2>/dev/null || true); "
                + "if echo \"$STATE\" | grep -q 'connected=true' && echo \"$STATE\" | grep -q 'handshake_verified=true' "
                + "&& echo \"$STATE\" | grep -q 'protocol_compatible=true' "
                + "&& echo \"$STATE\" | grep -Eq \"daemon_pid=$PID[, }]\" "
                + "&& echo \"$STATE\" | grep -Eq \"activation_token=$TOKEN[, }]\"; then echo \"EXTBRIDGE_READY:$TOKEN:$PID\"; exit 0; fi; "
                + "sleep 0.2; done; echo ACTIVATION_NOT_VERIFIED; exit 24";
        String encoded = java.util.Base64.getEncoder().encodeToString(script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "adb shell \"echo " + encoded + " | base64 -d | sh\"";
    }
}
