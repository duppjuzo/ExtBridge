package com.extbridge.helper;

/** Deploys the daemon through the user's authorized shell. Root only supplies the JAR bytes. */
public final class DaemonLaunchScript {
    private DaemonLaunchScript() {}

    private static final String TMP = "/data/local/tmp/";
    private static final String LIVE = TMP + "extbridge.jar";
    private static final String MAIN = "app_process /data/local/tmp com.extbridge.daemon.ExtBridge";
    private static final String PROCESS = "^(/system/bin/)?app_process /data/local/tmp com[.]extbridge[.]daemon[.]ExtBridge";
    private static final String HELPER = "com.extbridge.helper";
    private static final String PC_TOKEN = java.util.UUID.randomUUID().toString().replace("-", "");

    public static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    private static String verify(String jar, int user) {
        return "timeout 8 env CLASSPATH=" + jar + " " + MAIN + " --verify-helper --helper-user " + user;
    }

    // Only called after the staged archive and the installed helper have passed preflight.
    private static String replaceAndGrant(String jar, int user, String token) {
        return "(content call --user " + user + " --uri content://com.extbridge.helper.provider --method stopForActivation --arg " + token + " >/dev/null 2>&1 || true) && "
                + "(pkill -f '" + PROCESS + "' 2>/dev/null || true) && sleep 0.4 && "
                + "{ ! pgrep -f '" + PROCESS + "' >/dev/null || { echo OLD_DAEMON_NOT_STOPPED; exit 23; }; } && "
                + "mv -f " + jar + " " + LIVE + " && "
                + "(appops set --user " + user + " " + HELPER + " SYSTEM_ALERT_WINDOW allow 2>/dev/null || true) && "
                + "(appops set --user " + user + " " + HELPER + " RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true) && "
                + "(appops set --user " + user + " " + HELPER + " 10021 allow 2>/dev/null || true) && "
                + "(appops set --user " + user + " " + HELPER + " ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null || true) && "
                + "(pm grant --user " + user + " " + HELPER + " android.permission.POST_NOTIFICATIONS 2>/dev/null || true) && "
                + "(dumpsys deviceidle whitelist +" + HELPER + " >/dev/null 2>&1 || true)";
    }

    private static String start(String token, int user) {
        return "env CLASSPATH=" + LIVE + " nohup " + MAIN + " --activation-token " + token + " --helper-user " + user
                + " </dev/null >" + TMP + "extbridge.log 2>&1";
    }

    public static String build(String token, String stagedJar, String sha256, int user, String cancelFile, boolean root) {
        if (!token.matches("[a-zA-Z0-9_-]{16,80}") || !sha256.matches("[a-f0-9]{64}") || user < 0) {
            throw new IllegalArgumentException("Invalid activation identity");
        }
        String dir = TMP + "extbridge." + token;
        String jar = dir + "/daemon.jar";
        String staged = quote(stagedJar);
        String cancel = cancelFile == null ? "" : "if [ -e " + quote(cancelFile) + " ]; then exit 125; fi; ";
        String deploy = "[ \"$(id -u):$(id -g)\" = 2000:2000 ] || { echo ERR_SHELL_ID; exit 12; }; "
                + "umask 077; mkdir -m 700 " + dir + " || exit 21; "
                + "trap " + quote("rm -f " + jar + (root ? "" : " " + staged) + "; rmdir " + dir) + " EXIT; "
                + "cat " + (root ? "" : staged) + " > " + jar + " && chmod 444 " + jar + " || exit 21; "
                + "[ \"$(sha256sum " + jar + " | cut -d' ' -f1)\" = " + sha256 + " ] || { echo ERR_STAGE_HASH; exit 21; }; "
                + verify(jar, user) + " || { echo ERR_VERIFY_HELPER; exit 21; }; " + cancel
                + replaceAndGrant(jar, user, token) + " || exit 22; " + cancel
                + start(token, user) + " & PID=$!; sleep 0.6; "
                + "kill -0 \"$PID\" 2>/dev/null || { echo EXTBRIDGE_LAUNCH_FAILED; exit 24; }; "
                + "echo EXTBRIDGE_LAUNCH:" + token + ":$PID";
        if (!root) return deploy;

        // A mount-isolated su may not see the app's private file. Only read as root;
        // even the preflight app_process is run as shell, after checking the received digest.
        String supply = "supply_jar() { if [ -f " + staged + " ] && [ ! -L " + staged + " ] && "
                + "[ \"$(sha256sum " + staged + " 2>/dev/null | cut -d' ' -f1)\" = " + sha256 + " ]; then cat " + staged + "; return; fi; "
                + "for p in $(pm path --user " + user + " " + HELPER + " | sed -n 's/^package://p' | tr -d '\\r'); do "
                + "[ -f \"$p\" ] && [ ! -L \"$p\" ] || continue; "
                + "[ \"$(unzip -p \"$p\" assets/extbridge.jar 2>/dev/null | sha256sum | cut -d' ' -f1)\" = " + sha256 + " ] || continue; "
                + "unzip -p \"$p\" assets/extbridge.jar; return; done; return 21; }; ";
        // Select an available su syntax *before* starting the transaction. Never retry a
        // partially completed deployment or fall back to executing in the root shell.
        return "[ \"$(id -u)\" = 0 ] || { echo NO_ROOT; exit 12; }; " + cancel + supply
                + "for form in uid name sh; do case $form in uid) set -- su 2000 -c;; name) set -- su shell -c;; sh) set -- su 2000 /system/bin/sh -c;; esac; "
                + "[ \"$(\"$@\" 'id -u' 2>/dev/null)\" = 2000 ] || continue; "
                + "supply_jar | \"$@\" " + quote(deploy) + "; exit $?; done; echo ERR_SHELL_ID; exit 12";
    }

    /** No host-shell interpolation: can be pasted into PowerShell, cmd.exe or a POSIX shell. */
    public static String pcCommand(int user) {
        if (user < 0) throw new IllegalArgumentException("Invalid helper user");
        String dir = TMP + "extbridge.pc." + PC_TOKEN;
        String jar = dir + "/daemon.jar", apk = dir + "/source.apk";
        return "adb shell \"umask 077; mkdir -m 700 " + dir + " || exit 21; "
                + "trap 'rm -f " + apk + " " + jar + "; rmdir " + dir + "' EXIT; "
                + "pm path --user " + user + " " + HELPER + " | grep '^package:' | cut -d: -f2 | head -n1 | xargs cat > " + apk + " && "
                + "test -s " + apk + " && unzip -p " + apk + " assets/extbridge.jar > " + jar + " && chmod 444 " + jar + " && "
                + verify(jar, user) + " && " + replaceAndGrant(jar, user, PC_TOKEN) + " && "
                + "(" + start(PC_TOKEN, user) + " &) && sleep 0.6 && "
                + "if pgrep -f '" + PROCESS + " --activation-token " + PC_TOKEN + "' >/dev/null; then echo EXTBRIDGE_PC_READY; else echo EXTBRIDGE_PC_FAILED; exit 24; fi\"";
    }
}
