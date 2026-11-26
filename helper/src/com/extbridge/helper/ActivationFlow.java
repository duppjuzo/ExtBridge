package com.extbridge.helper;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The same staged state machine runs in production and in independent unit tests. */
public final class ActivationFlow {
    public enum Stage { VALIDATE, ADB_SERVER, PAIR, CONNECT, DEPLOY, VERIFY }
    public enum Outcome { ACTIVATED, ALREADY_RUNNING, PAIRED_NEEDS_CONNECT, FAILED, CANCELLED, BUSY }
    public static final class Command {
        public final int code; public final String output;
        public Command(int code, String output) { this.code = code; this.output = output == null ? "" : output; }
    }
    public static final class Request {
        public final ActivationSession.Mode mode;
        public final String host, code; public final int pairPort, connectPort;
        public Request(ActivationSession.Mode mode, String host, int pairPort, String code, int connectPort) {
            this.mode = mode; this.host = host == null ? "" : host.trim(); this.pairPort = pairPort;
            this.code = code == null ? "" : code.trim(); this.connectPort = connectPort;
        }
    }
    public static final class Probe {
        public final boolean connected, handshake, compatible;
        public final int pid, uid; public final String token, issue;
        public Probe(boolean connected, boolean handshake, boolean compatible, int pid, int uid, String token, String issue) {
            this.connected = connected; this.handshake = handshake; this.compatible = compatible; this.pid = pid;
            this.uid = uid; this.token = token == null ? "" : token; this.issue = issue == null ? "" : issue;
        }
    }
    public static final class Result {
        public final Outcome outcome; public final Stage stage; public final String message, token;
        public final int pid;
        Result(Outcome outcome, Stage stage, String message, String token, int pid) {
            this.outcome = outcome; this.stage = stage; this.message = message; this.token = token; this.pid = pid;
        }
        public boolean activated() { return outcome == Outcome.ACTIVATED; }
    }
    public static final class StagedJar {
        final String path, sha256;
        public StagedJar(String path, String sha256) { this.path = path; this.sha256 = sha256; }
    }
    public interface Environment {
        boolean cancelled();
        boolean adbAvailable();
        Command startAdbServer();
        default boolean hostAllowed(String host) { return true; }
        Command adb(String... args);
        Command root(String script);
        String script(String token, String stagedJar, String sha256, boolean root);
        StagedJar stagedJar();
        int discoverConnectPort();
        Probe probe();
        long now();
        void pause(long millis);
        void step(Stage stage, String message);
    }
    private static final class Cancelled extends RuntimeException {}
    private static void check(Environment env) { if (env.cancelled()) throw new Cancelled(); }
    public static boolean validPort(int value) { return value >= 1 && value <= 65535; }
    public static boolean validCode(String value) { return value != null && value.matches("[0-9]{6}"); }
    public static boolean validHost(String value) {
        if (value == null || value.isEmpty() || value.length() > 80) return false;
        if (value.contains(":")) {
            if (!value.matches("[0-9a-fA-F:]+")) return false;
            try { return java.net.InetAddress.getByName(value) instanceof java.net.Inet6Address; } catch (Exception ignored) { return false; }
        }
        if (!value.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) return false;
        for (String part : value.split("\\.")) if (Integer.parseInt(part) > 255) return false;
        return true;
    }
    public static Request notification(String raw, String host, int pairingPort, int connectPort) {
        String text = raw == null ? "" : raw.trim().replace('：', ':');
        String code = "";
        if (validCode(text)) code = text;
        else {
            Matcher codeFirst = Pattern.compile("^([0-9]{6})[:\\s]+([0-9]{1,5})$").matcher(text);
            Matcher portFirst = Pattern.compile("^([0-9]{1,5})[:\\s]+([0-9]{6})$").matcher(text);
            String[] parts = text.split("\\s+");
            if (codeFirst.matches()) { code = codeFirst.group(1); pairingPort = Integer.parseInt(codeFirst.group(2)); }
            else if (portFirst.matches()) { pairingPort = Integer.parseInt(portFirst.group(1)); code = portFirst.group(2); }
            else if (parts.length == 3 && validHost(parts[0]) && parts[1].matches("[0-9]{1,5}") && validCode(parts[2])) {
                host = parts[0]; pairingPort = Integer.parseInt(parts[1]); code = parts[2];
            }
        }
        return new Request(ActivationSession.Mode.NOTIFICATION, host, pairingPort, code, connectPort);
    }
    public static String target(String host, int port) { return (host.contains(":") ? "[" + host + "]" : host) + ":" + port; }
    public static boolean paired(Command result, String target) {
        return result.code == 0 && Pattern.compile("(?im)^successfully paired to " + Pattern.quote(target) + "(?:\\s|$)").matcher(result.output.trim()).find()
                && !hasError(result.output);
    }
    public static boolean connected(Command result, String target) {
        String text = result.output.trim().toLowerCase(Locale.ROOT);
        String address = target.toLowerCase(Locale.ROOT);
        if (result.code != 0 || hasError(text)) return false;
        for (String line : text.split("\\r?\\n"))
            if (line.trim().equals("connected to " + address) || line.trim().equals("already connected to " + address)) return true;
        return false;
    }
    private static boolean hasError(String output) {
        String text = output.toLowerCase(Locale.ROOT);
        return text.contains("failed") || text.contains("error") || text.contains("cannot connect") || text.contains("unable to connect");
    }
    public static int launchedPid(Command command, String token) {
        if (command.code != 0) return -1;
        Matcher match = Pattern.compile("(?m)^EXTBRIDGE_LAUNCH:" + Pattern.quote(token) + ":([0-9]+)\\s*$").matcher(command.output);
        if (!match.find()) return -1;
        try { int pid = Integer.parseInt(match.group(1)); return pid > 0 ? pid : -1; } catch (NumberFormatException ignored) { return -1; }
    }
    public static boolean owns(Probe probe, String token, int pid) {
        return probe != null && probe.connected && probe.handshake && probe.compatible && probe.pid == pid
                && probe.uid == 2000 && token.equals(probe.token);
    }
    private static Result result(Outcome outcome, Stage stage, String message, String token, int pid) {
        return new Result(outcome, stage, message, token, pid);
    }
    public static Result busy() { return result(Outcome.BUSY, Stage.VALIDATE, "另一项激活正在进行", "", -1); }

    public static Result run(Request request, String token, Environment env) {
        Stage stage = Stage.VALIDATE;
        try {
            check(env);
            if (request.mode == ActivationSession.Mode.AUTO_ROOT) {
                Probe existing = env.probe(); check(env);
                if (existing != null && existing.connected)
                    return result(Outcome.ALREADY_RUNNING, stage, existing.compatible ? "已有服务运行，没有重复激活" : "已有服务需更新，请手动重新激活", token, existing.pid);
                if (existing != null && !existing.issue.isEmpty())
                    return result(Outcome.FAILED, stage, "服务状态无法确认，请手动激活", token, -1);
            }
            boolean root = request.mode == ActivationSession.Mode.ROOT || request.mode == ActivationSession.Mode.AUTO_ROOT;
            if (!root && (!validHost(request.host) || !validPort(request.connectPort) && request.mode == ActivationSession.Mode.CONNECT))
                return result(Outcome.FAILED, stage, "请填写有效的本机地址和连接端口", token, -1);
            if (!root && !env.hostAllowed(request.host)) return result(Outcome.FAILED, stage, "请使用本机无线调试地址", token, -1);
            boolean pair = request.mode == ActivationSession.Mode.PAIR || request.mode == ActivationSession.Mode.NOTIFICATION;
            if (pair && (!validPort(request.pairPort) || !validCode(request.code)))
                return result(Outcome.FAILED, stage, "请填写有效的配对端口和六位配对码", token, -1);
            String address = "";
            if (!root) {
                if (!env.adbAvailable()) return result(Outcome.FAILED, stage, "内置 ADB 不可用", token, -1);
                stage = Stage.ADB_SERVER; env.step(stage, "正在启动 ADB 通道…"); check(env);
                Command server = env.startAdbServer(); check(env);
                if (server.code != 0) return result(Outcome.FAILED, stage, "ADB 通道启动失败", token, -1);
                int port = request.connectPort;
                if (pair) {
                    stage = Stage.PAIR; env.step(stage, "正在配对设备…"); check(env);
                    Command paired = env.adb("pair", target(request.host, request.pairPort), request.code); check(env);
                    if (!paired(paired, target(request.host, request.pairPort))) return result(Outcome.FAILED, stage, "设备配对失败，请检查配对码和端口", token, -1);
                    if (!validPort(port)) {
                        long deadline = env.now() + 4500;
                        while (env.now() < deadline) {
                            check(env); port = env.discoverConnectPort(); if (validPort(port)) break; env.pause(200);
                        }
                    }
                    if (!validPort(port)) return result(Outcome.PAIRED_NEEDS_CONNECT, stage,
                            "设备已配对，但尚未激活。请填写连接端口后直接连接", token, -1);
                }
                stage = Stage.CONNECT; env.step(stage, "正在连接无线调试…"); check(env);
                address = target(request.host, port);
                Command connection = env.adb("connect", address); check(env);
                if (!connected(connection, address)) return result(Outcome.FAILED, stage, "无线调试连接失败", token, -1);
            }
            stage = Stage.DEPLOY; env.step(stage, "正在启动后台服务…"); check(env);
            StagedJar staged = env.stagedJar();
            String deviceJar = null;
            if (!root) {
                // Push the staged jar directly so the deploy script never depends on
                // pm path + unzip, which fails under MIUI 分身 / unopened security settings.
                // Per-token name: no stale jar from a previous activation can be picked up.
                deviceJar = "/data/local/tmp/extb_" + token + ".jar";
                Command pushed = env.adb("-s", address, "push", staged.path, deviceJar); check(env);
                if (pushed.code != 0) return result(Outcome.FAILED, stage, "守护进程 JAR 传输失败", token, -1);
            }
            String script = env.script(token, root ? staged.path : deviceJar, staged.sha256, root); check(env);
            Command launch = root ? env.root(script) : env.adb("-s", address, "shell", script); check(env);
            int pid = launchedPid(launch, token);
            if (pid < 0) return result(Outcome.FAILED, stage,
                    launch.output.contains("ERR_VERIFY_HELPER") ? "无法验证当前用户的官方应用签名，未替换原服务"
                            : launch.output.contains("ERR_STAGE_HASH") ? "守护进程 JAR 校验失败，未替换原服务"
                            : launch.output.contains("ERR_SHELL_ID") ? "无法以 shell 身份启动，已停止激活"
                            : launch.code == -3 ? "服务启动命令超时" : root && (launch.code == -2 || launch.output.contains("NO_ROOT")
                            || launch.output.toLowerCase(Locale.ROOT).contains("denied")) ? "未获得可用的 Root 授权"
                            : "服务启动失败，请查看服务日志", token, -1);
            stage = Stage.VERIFY; env.step(stage, "正在验证本次启动的服务…");
            long deadline = env.now() + 9000;
            Probe last = null;
            while (env.now() < deadline) {
                check(env); last = env.probe(); check(env);
                if (owns(last, token, pid)) return result(Outcome.ACTIVATED, stage, "本次激活成功", token, pid);
                env.pause(200);
            }
            String message = last != null && last.connected ? "已有服务仍在运行，但本次启动未完成"
                    : last != null && last.handshake ? "已收到握手，但本次服务状态校验未通过"
                    : "本次服务未建立可用连接，请查看服务日志";
            return result(Outcome.FAILED, stage, message, token, pid);
        } catch (Cancelled ignored) {
            return result(Outcome.CANCELLED, stage, "激活已取消", token, -1);
        } catch (Throwable error) {
            return result(Outcome.FAILED, stage, "激活失败：" + error.getClass().getSimpleName(), token, -1);
        }
    }
}
