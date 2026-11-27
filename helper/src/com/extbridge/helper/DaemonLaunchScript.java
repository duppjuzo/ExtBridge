package com.extbridge.helper;

/** Deploys the daemon through the user's authorized shell. Root only supplies the JAR bytes. */
public final class DaemonLaunchScript {
    private DaemonLaunchScript() {}

    private static final String TMP = "/data/local/tmp/";
    private static final String LIVE = TMP + "extbridge.jar";
    private static final String LOG = TMP + "extbridge.log";
    private static final String APP_PROCESS = "/system/bin/app_process";
    private static final String SHELL_DOMAIN = "u:r:shell:s0";
    private static final String MAIN_ARGS = " /data/local/tmp com.extbridge.daemon.ExtBridge";
    private static final String PROCESS = "^(/system/bin/)?app_process /data/local/tmp com[.]extbridge[.]daemon[.]ExtBridge";
    private static final String SHELL_ID = "[ \"$(id -u):$(id -g)\" = 2000:2000 ]";
    private static final String HELPER = "com.extbridge.helper";
    private static final String ROOT_APPROVAL = "GO";
    private static final String PC_TOKEN = java.util.UUID.randomUUID().toString().replace("-", "");

    public static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    private static String verify(String jar, int user, String launcher) {
        return "timeout 8 env CLASSPATH=" + jar + " " + launcher + MAIN_ARGS + " --verify-helper --helper-user " + user + " </dev/null";
    }

    // Only called after the staged archive and the installed helper have passed preflight.
    private static String replaceAndGrant(String jar, int user, String token) {
        return "(timeout 4 content call --user " + user + " --uri content://com.extbridge.helper.provider --method stopForActivation --arg " + token + " >/dev/null 2>&1 || true) && "
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

    private static String daemonOptions(String token, int user) {
        return " --activation-token " + token + " --helper-user " + user;
    }

    private static String start(String token, int user, String launcher) {
        String exec = "exec env CLASSPATH=" + LIVE + " nohup " + launcher + MAIN_ARGS + daemonOptions(token, user) + " </dev/null";
        // Older Root installs may have left a root-owned log. Never truncate it in place
        // or let an unusable log prevent app_process from starting.
        return "(rm -f " + LOG + " 2>/dev/null; if (set -C; : > " + LOG + ") 2>/dev/null && test -f " + LOG
                + " && test ! -L " + LOG + "; then " + exec + " >>" + LOG + " 2>&1; else " + exec + " >/dev/null 2>&1; fi)";
    }

    private static void checkIdentity(String token, int user) {
        if (token == null || !token.matches("[a-zA-Z0-9_-]{16,80}") || user < 0) throw new IllegalArgumentException("Invalid activation identity");
    }

    public static String rootReady(String token) { return "EXTBRIDGE_ROOT_READY:" + token; }
    public static String rootStarted(String token) { return "EXTBRIDGE_ROOT_STARTED:" + token; }
    public static String rootApproval() { return ROOT_APPROVAL + "\n"; }

    private static String awaitApproval(String marker) {
        return "echo " + marker + "; IFS= read -r approval && [ \"$approval\" = " + ROOT_APPROVAL + " ] || exit 125; ";
    }

    public static String build(String token, String stagedJar, String sha256, int user, long rootJarBytes, String su) {
        checkIdentity(token, user);
        boolean root = su != null;
        if (!sha256.matches("[a-f0-9]{64}") || (root && rootJarBytes <= 0)) throw new IllegalArgumentException("Invalid staged JAR");
        String dir = TMP + "extbridge." + token;
        String jar = dir + "/daemon.jar";
        String staged = quote(stagedJar);
        String cleanup = (root ? "if [ -n \"$PID\" ]; then kill \"$PID\" 2>/dev/null; fi; " : "")
                + "rm -f " + jar + (root ? "" : " " + staged) + "; rmdir " + dir;
        String deploy = SHELL_ID + " || { echo ERR_SHELL_ID; exit 12; }; "
                + "umask 077; mkdir -m 700 " + dir + " || exit 21; "
                + (root ? "PID=; " : "") + "trap " + quote(cleanup) + " EXIT; "
                + (root ? "head -c " + rootJarBytes : "cat " + staged) + " > " + jar + " && chmod 444 " + jar + " || exit 21; "
                + "[ \"$(sha256sum " + jar + " | cut -d' ' -f1)\" = " + sha256 + " ] || { echo ERR_STAGE_HASH; exit 21; }; "
                + verify(jar, user, APP_PROCESS) + " || { case $? in 12) echo ERR_SHELL_ID; exit 12;; *) echo ERR_VERIFY_HELPER; exit 21;; esac; }; "
                + (root ? awaitApproval(rootReady(token)) : "")
                + "{ " + replaceAndGrant(jar, user, token) + "; } </dev/null || exit 22; "
                + start(token, user, APP_PROCESS) + " & PID=$!; sleep 0.6; "
                + "kill -0 \"$PID\" 2>/dev/null || { echo EXTBRIDGE_LAUNCH_FAILED; exit 24; }; "
                + (root ? awaitApproval(rootStarted(token)) : "")
                + "echo EXTBRIDGE_LAUNCH:" + token + ":$PID; PID=";
        if (!root) return deploy;

        // A mount-isolated su may not see the app's private file. Only read as root;
        // even the preflight app_process is run as shell, after checking the received digest.
        String supply = "supply_jar() { if [ -f " + staged + " ] && [ ! -L " + staged + " ] && "
                + "[ \"$(sha256sum " + staged + " 2>/dev/null | cut -d' ' -f1)\" = " + sha256 + " ]; then cat " + staged + "; return; fi; "
                + "for p in $(pm path --user " + user + " " + HELPER + " 2>/dev/null | sed -n 's/^package://p' | tr -d '\\r') "
                + "$(pm path " + HELPER + " 2>/dev/null | sed -n 's/^package://p' | tr -d '\\r'); do "
                + "[ -f \"$p\" ] && [ ! -L \"$p\" ] || continue; "
                + "[ \"$(unzip -p \"$p\" assets/extbridge.jar 2>/dev/null | sha256sum | cut -d' ' -f1)\" = " + sha256 + " ] || continue; "
                + "unzip -p \"$p\" assets/extbridge.jar; return; done; return 21; }; ";
        // The shell reads exactly the JAR length, then waits for app approvals. No app-private
        // path needs to be readable as UID 2000. EOF denies both gates, even without Binder.
        String relay = "{ supply_jar || exit 21; for gate in replace keep; do "
                + "IFS= read -r -t 22 approval || break; printf '%s\\n' \"$approval\" || break; done; }";
        return supply + asShell(su, relay + " | ", deploy);
    }

    private static String asShell(String su, String stdin, String command) {
        String binary = quote(su);
        String inDomain = "[ \"$(id -Z 2>/dev/null)\" = " + SHELL_DOMAIN + " ]";
        // Check from inside the selected shell, before it reads the JAR or touches a running service.
        String guarded = inDomain + " || { echo ERR_SHELL_DOMAIN; exit 12; }; " + command;
        String enter = "set -- /system/bin/sh -c " + quote(guarded) + "; if " + inDomain + "; then exec \"$@\"; fi; "
                + "runcon " + SHELL_DOMAIN + " \"$@\"; rc=$?; case $rc in 1|126|127) echo ERR_SHELL_DOMAIN; exit 12;; esac; exit $rc";
        // Select a working su form before any mutation. Never retry a partially completed
        // transaction or let the root shell load the daemon.
        return "[ \"$(id -u)\" = 0 ] || { echo NO_ROOT; exit 12; }; "
                + "for form in uid name sh; do case $form in uid) set -- " + binary + " 2000 -c;; name) set -- " + binary
                + " shell -c;; sh) set -- " + binary + " 2000 /system/bin/sh -c;; esac; "
                + "\"$@\" " + quote(SHELL_ID) + " </dev/null 2>/dev/null || continue; "
                + stdin + "\"$@\" " + quote(enter) + "; exit $?; done; echo ERR_SHELL_ID; exit 12";
    }

    public static String stopOwnedRoot(String token, int user, String su) {
        checkIdentity(token, user);
        String stop = SHELL_ID + " && pkill -f " + quote(PROCESS + daemonOptions(token, user) + "$");
        return asShell(su, "", stop);
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
                + verify(jar, user, APP_PROCESS) + " && " + replaceAndGrant(jar, user, PC_TOKEN) + " && "
                + "(" + start(PC_TOKEN, user, APP_PROCESS) + " &) && sleep 0.6 && "
                + "if pgrep -f '" + PROCESS + " --activation-token " + PC_TOKEN + "' >/dev/null; then echo EXTBRIDGE_PC_READY; else echo EXTBRIDGE_PC_FAILED; exit 24; fi\"";
    }
}
