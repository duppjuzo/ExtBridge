package com.extbridge.helper;

/** Fail-closed deployment shared by Root, native ADB and copied computer commands. */
public final class DaemonLaunchScript {
    private DaemonLaunchScript() {}

    public static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    /**
     * Multi-split / multi-user `pm path` walk used as the Magisk isolated-namespace fallback
     * (mirrors main's buildMultiApkUnzipFallback): tries every base APK path `pm path`
     * reports for user 0 and the current user, skips symlinks/missing files, and stops at
     * the first archive that yields a non-empty assets/extbridge.jar.
     */
    private static String multiApkUnzip(String dstExpr) {
        return "for p in $(pm path --user 0 com.extbridge.helper 2>/dev/null | sed -n 's/^package://p' | tr -d '\\r') "
                + "$(pm path com.extbridge.helper 2>/dev/null | sed -n 's/^package://p' | tr -d '\\r'); do "
                + "if [ -n \"$p\" ] && [ -f \"$p\" ] && [ ! -L \"$p\" ]; then "
                + "unzip -p \"$p\" assets/extbridge.jar > " + dstExpr + " 2>/dev/null; "
                + "[ -s " + dstExpr + " ] && break; fi; done";
    }

    /** Liveness patterns: anchored so the deploying shell itself is never matched. */
    private static final String ALIVE =
            "(pgrep -f '^app_process /data/local/tmp com.extbridge.daemon.ExtBridge' >/dev/null 2>&1 "
                    + "|| pgrep -f '^/system/bin/app_process /data/local/tmp com.extbridge.daemon.ExtBridge' >/dev/null 2>&1)";

    public static String build(String token, String stagedJar, String cancelFile, boolean root) {
        if (token == null || !token.matches("[a-zA-Z0-9_-]{16,80}")) throw new IllegalArgumentException("Invalid activation token");
        // Literal per-activation paths only: environment variables do not survive `su 2000 -c`,
        // and every later stage re-uses these literals instead of $TMP/$TOKEN.
        String tmp = "/data/local/tmp/extbridge." + token + ".new.jar";
        String tmpQ = quote(tmp);
        String tokenQ = quote(token);
        String cancelGuard = cancelFile == null ? "" : "if [ -e " + quote(cancelFile) + " ]; then exit 125; fi; ";

        // ---- jar acquisition: staged file (root cat / adb push) first, multi-apk fallback ----
        String extraction;
        if (stagedJar != null && !stagedJar.isEmpty()) {
            String qStage = quote(stagedJar.replace("'", ""));
            extraction = "if [ -f " + qStage + " ] && [ ! -L " + qStage + " ] && [ -s " + qStage + " ]; then "
                    + "cat " + qStage + " > " + tmpQ + "; "
                    + "else " + multiApkUnzip(tmpQ) + "; fi; ";
        } else {
            extraction = multiApkUnzip(tmpQ) + "; ";
        }

        // ---- validate the staged archive, then stop any old daemon before touching the live jar ----
        String stopOld =
                "if [ ! -f " + tmpQ + " ] || [ -L " + tmpQ + " ] || [ ! -s " + tmpQ + " ]; then "
                        + "rm -f " + tmpQ + " 2>/dev/null; echo ERR_STAGE_EMPTY; exit 21; fi; "
                        + "if [ \"$(dd if=" + tmpQ + " bs=1 count=2 2>/dev/null)\" != \"PK\" ]; then "
                        + "rm -f " + tmpQ + " 2>/dev/null; echo ERR_STAGE_NOT_ZIP; exit 21; fi; "
                        + "chmod 644 " + tmpQ + " || { rm -f " + tmpQ + " 2>/dev/null; echo ERR_CHMOD_JAR; exit 21; }; "
                        + "VERIFY=$(CLASSPATH=" + tmpQ + " timeout 5 app_process /data/local/tmp com.extbridge.daemon.ExtBridge --verify 2>/dev/null); "
                        + "if [ \"$VERIFY\" != 'EXTBRIDGE_JAR_OK:2' ]; then "
                        + "rm -f " + tmpQ + " 2>/dev/null; echo ERR_VERIFY:$VERIFY; exit 21; fi; "
                        + "content call --uri content://com.extbridge.helper.provider --method stopForActivation --arg " + tokenQ + " >/dev/null 2>&1 || true; "
                        + "am broadcast -a com.extbridge.daemon.SHUTDOWN -p com.android.shell >/dev/null 2>&1 || true; "
                        + "sleep 0.4; "
                        + "(pkill -f '^app_process /data/local/tmp com.extbridge.daemon.ExtBridge' 2>/dev/null; true); "
                        + "(pkill -f '^/system/bin/app_process /data/local/tmp com.extbridge.daemon.ExtBridge' 2>/dev/null; true); "
                        + "sleep 0.3; "
                        + "if " + ALIVE + "; then echo OLD_DAEMON_NOT_STOPPED; exit 23; fi; "
                        + "rm -f /data/local/tmp/extbridge.jar; "
                        + "if ! mv -f " + tmpQ + " /data/local/tmp/extbridge.jar || [ -L /data/local/tmp/extbridge.jar ] || [ ! -s /data/local/tmp/extbridge.jar ]; then "
                        + "rm -f " + tmpQ + " 2>/dev/null; echo ERR_SWAP_JAR; exit 22; fi; "
                        + "chmod 644 /data/local/tmp/extbridge.jar 2>/dev/null; "
                        + (root
                        ? "chown -h 2000:2000 /data/local/tmp/extbridge.jar /data/local/tmp/extbridge.conf /data/local/tmp/extbridge_apps.conf 2>/dev/null || true; "
                        : "")
                        + (stagedJar != null && !stagedJar.isEmpty() && !root
                        ? "rm -f " + quote(stagedJar.replace("'", "")) + " 2>/dev/null; "
                        : "");

        // ---- Xiaomi-required grants: appops 10021 + deviceidle whitelist (+ the usual set) ----
        String grants =
                "appops set com.extbridge.helper SYSTEM_ALERT_WINDOW allow 2>/dev/null; "
                        + "appops set com.extbridge.helper RUN_ANY_IN_BACKGROUND allow 2>/dev/null; "
                        + "appops set com.extbridge.helper 10021 allow 2>/dev/null; "
                        + "appops set com.extbridge.helper ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null; "
                        + "pm grant com.extbridge.helper android.permission.POST_NOTIFICATIONS 2>/dev/null; "
                        + "dumpsys deviceidle whitelist +com.extbridge.helper >/dev/null 2>&1; ";

        // ---- launch: PID must echo back to the caller for ActivationFlow.owns() verification ----
        // nohup execs app_process in place, so $! IS the daemon pid the probe later reports.
        String launch;
        if (root) {
            // Root only stages grants and swaps the jar; the daemon itself is started through
            // `su 2000 -c` so it never runs as uid 0 (rc 20-29 propagate as activation failures).
            String cancelKill = cancelFile == null ? "" : "if [ -e " + quote(cancelFile) + " ]; then kill \"$PID\" 2>/dev/null; exit 125; fi; ";
            String inner = "rm -f /data/local/tmp/extbridge.log 2>/dev/null; "
                    + "CLASSPATH=/data/local/tmp/extbridge.jar nohup app_process /data/local/tmp com.extbridge.daemon.ExtBridge"
                    + " --activation-token " + tokenQ
                    + (cancelFile != null ? " --cancel-file " + quote(cancelFile) : "")
                    + " </dev/null >>/data/local/tmp/extbridge.log 2>&1 & PID=$!; sleep 0.5; "
                    + cancelKill
                    + "if " + ALIVE + "; then echo \"EXTBRIDGE_LAUNCH:" + token + ":$PID\"; "
                    + "else echo EXTBRIDGE_LAUNCH_FAILED; cat /data/local/tmp/extbridge.log 2>/dev/null; exit 24; fi";
            String qInner = inner.replace("'", "'\\''");
            launch = "run_as_shell() { "
                    + "su 2000 -c \"$1\" && return 0; rc=$?; [ \"$rc\" -ge 20 ] && [ \"$rc\" -le 29 ] && return \"$rc\"; "
                    + "su shell -c \"$1\" 2>/dev/null && return 0; rc=$?; [ \"$rc\" -ge 20 ] && [ \"$rc\" -le 29 ] && return \"$rc\"; "
                    + "su 2000 /system/bin/sh -c \"$1\" 2>/dev/null && return 0; rc=$?; [ \"$rc\" -ge 20 ] && [ \"$rc\" -le 29 ] && return \"$rc\"; "
                    + "/system/bin/sh -c \"$1\"; "
                    + "}; "
                    + "run_as_shell '" + qInner + "'; RC=$?; exit $RC";
        } else {
            String cancelKill = cancelFile == null ? "" : "if [ -e " + quote(cancelFile) + " ]; then kill \"$PID\" 2>/dev/null; exit 125; fi; ";
            launch = "rm -f /data/local/tmp/extbridge.log 2>/dev/null; "
                    + "CLASSPATH=/data/local/tmp/extbridge.jar nohup app_process /data/local/tmp com.extbridge.daemon.ExtBridge"
                    + " --activation-token " + tokenQ
                    + " </dev/null >/data/local/tmp/extbridge.log 2>&1 & PID=$!; sleep 0.5; "
                    + cancelKill
                    + "if " + ALIVE + "; then echo \"EXTBRIDGE_LAUNCH:" + token + ":$PID\"; "
                    + "else echo EXTBRIDGE_LAUNCH_FAILED; cat /data/local/tmp/extbridge.log 2>/dev/null; exit 24; fi";
        }

        // No `set -e`: every failure above is an explicit gated exit with a marker, and the
        // best-effort grants (appops/pm grant) must never abort a deploy mid-way.
        return (root ? "test \"$(id -u)\" = 0 || { echo NO_ROOT; exit 12; }; " : "")
                + cancelGuard
                + extraction
                + stopOld
                + cancelGuard
                + grants
                + launch;
    }

    /**
     * Plain-text single computer ADB command, aligned with main's buildPcAdbCommand():
     * no Base64, no /proc/sys/kernel/random/uuid, and no dependency on the app UI being
     * open. The APK is copied out via `xargs cat` (unzip then reads /data/local/tmp, not
     * SELinux-guarded /data/app), the jar is verified with the daemon liveness probe,
     * any old daemon is stopped, Xiaomi-required appops are granted, and success is
     * reported by process liveness instead of getDaemonStatus (which needs the app process).
     */
    public static String pcCommand() {
        return "adb shell \"rm -f /data/local/tmp/extb_src.apk /data/local/tmp/extbridge.jar.new && "
                + "pm path com.extbridge.helper | grep '^package:' | cut -d: -f2 | head -n1 | xargs cat > /data/local/tmp/extb_src.apk && "
                + "test -s /data/local/tmp/extb_src.apk && "
                + "unzip -p /data/local/tmp/extb_src.apk assets/extbridge.jar > /data/local/tmp/extbridge.jar.new && "
                + "rm -f /data/local/tmp/extb_src.apk && "
                + "test -s /data/local/tmp/extbridge.jar.new && "
                + "chmod 644 /data/local/tmp/extbridge.jar.new && "
                + "timeout 6 env CLASSPATH=/data/local/tmp/extbridge.jar.new app_process /data/local/tmp com.extbridge.daemon.ExtBridge --verify >/dev/null 2>&1 && "
                + "(content call --uri content://com.extbridge.helper.provider --method stopForActivation --arg EXTB_PC_ACTIVATION >/dev/null 2>&1 || true) && "
                + "(am broadcast -a com.extbridge.daemon.SHUTDOWN -p com.android.shell >/dev/null 2>&1 || true) && "
                + "sleep 0.4 && "
                + "(pkill -f '^app_process /data/local/tmp com.extbridge.daemon.ExtBridge' 2>/dev/null; true) && "
                + "(pkill -f '^/system/bin/app_process /data/local/tmp com.extbridge.daemon.ExtBridge' 2>/dev/null; true) && "
                + "sleep 0.2 && "
                + "rm -f /data/local/tmp/extbridge.jar && "
                + "mv -f /data/local/tmp/extbridge.jar.new /data/local/tmp/extbridge.jar && "
                + "test -s /data/local/tmp/extbridge.jar && "
                + "(appops set com.extbridge.helper SYSTEM_ALERT_WINDOW allow 2>/dev/null || true) && "
                + "(appops set com.extbridge.helper RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true) && "
                + "(appops set com.extbridge.helper 10021 allow 2>/dev/null || true) && "
                + "(appops set com.extbridge.helper ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null || true) && "
                + "(pm grant com.extbridge.helper android.permission.POST_NOTIFICATIONS 2>/dev/null || true) && "
                + "(dumpsys deviceidle whitelist +com.extbridge.helper >/dev/null 2>&1 || true) && "
                + "(CLASSPATH=/data/local/tmp/extbridge.jar nohup app_process /data/local/tmp com.extbridge.daemon.ExtBridge --activation-token EXTB_PC_ACTIVATION >/data/local/tmp/extbridge.log 2>&1 </dev/null &) && "
                + "sleep 0.6 && "
                + "if (pgrep -f '^app_process /data/local/tmp com.extbridge.daemon.ExtBridge' >/dev/null 2>&1 || pgrep -f '^/system/bin/app_process /data/local/tmp com.extbridge.daemon.ExtBridge' >/dev/null 2>&1); then echo EXTBRIDGE_PC_READY; else echo EXTBRIDGE_PC_FAILED; cat /data/local/tmp/extbridge.log 2>/dev/null; exit 24; fi\"";
    }
}
