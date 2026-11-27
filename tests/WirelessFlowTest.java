import com.extbridge.helper.ActivationFlow;
import com.extbridge.helper.ActivationSession;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** An already-paired transport. No command in this fixture runs on a device. */
public class WirelessFlowTest implements ActivationFlow.Environment {
    private static final String TOKEN = "wireless_test_ticket_01", ADDRESS = "127.0.0.1:41234";
    private static final String LOCAL = "local staging/daemon.jar", SHA = "a".repeat(64);
    private final String scenario;
    private final List<String> events = new ArrayList<>();
    private String remote;
    private boolean cancelled;

    WirelessFlowTest(String scenario) { this.scenario = scenario; }
    private static ActivationFlow.Command cmd(int code, String text) { return new ActivationFlow.Command(code, text); }
    private static void require(boolean ok) { if (!ok) throw new AssertionError(); }
    public boolean cancelled() { return cancelled; }
    public boolean adbAvailable() { return true; }
    public ActivationFlow.Command startAdbServer() { return cmd(0, ""); }
    public ActivationFlow.StagedJar stagedJar() { return new ActivationFlow.StagedJar(LOCAL, SHA); }
    public ActivationFlow.Command adb(String... args) {
        if (args[0].equals("connect")) return cmd(0, "connected to " + ADDRESS);
        require(args[0].equals("-s") && args[1].equals(ADDRESS));
        if (args[2].equals("push")) {
            events.add("push");
            require(args[3].equals(LOCAL) && args[4].contains(TOKEN));
            remote = args[4]; // includes any partially uploaded file
            cancelled = scenario.equals("cancel-after-push");
            return cmd(scenario.equals("direct") || cancelled ? 0 : 1, "");
        }
        require(args[2].equals("shell") && args[3].equals("verified-deploy"));
        events.add("deploy"); return cmd(0, "EXTBRIDGE_LAUNCH:" + TOKEN + ":123");
    }
    public ActivationFlow.Command adbWithInput(String file, String... args) {
        require(file.equals(LOCAL) && Arrays.asList(args).subList(0, 4).equals(Arrays.asList("-s", ADDRESS, "shell", "-T")));
        require(remote != null && args[4].contains(remote));
        events.add("stream"); cancelled = scenario.equals("cancel-during-stream");
        return cmd(scenario.equals("failed-stream") ? 1 : 0, "");
    }
    public String script(String token, String path, String digest, boolean root) {
        require(!root && token.equals(TOKEN) && path.equals(remote) && digest.equals(SHA));
        return "verified-deploy";
    }
    public void removeRemoteJar(String address, String path) {
        require(ADDRESS.equals(address) && path.equals(remote)); remote = null; events.add("remove");
    }
    public ActivationFlow.Probe probe() { return new ActivationFlow.Probe(true, true, true, 123, 2000, TOKEN, ""); }
    public void step(ActivationFlow.Stage stage, String message) {}
    public long now() { return 0; }
    public void pause(long millis) { throw new AssertionError("unexpected probe retry"); }
    public int discoverConnectPort() { throw new AssertionError(); }
    public ActivationFlow.Command root(String script) { throw new AssertionError(); }

    public static void main(String[] args) {
        WirelessFlowTest env = new WirelessFlowTest(args[0]);
        ActivationFlow.Result result = ActivationFlow.run(new ActivationFlow.Request(
                ActivationSession.Mode.CONNECT, "127.0.0.1", 0, "", 41234), TOKEN, env);
        require(result.outcome.name().equals(args[1]) && env.remote == null);
        if (!env.events.equals(Arrays.asList(args).subList(2, args.length))) throw new AssertionError(env.events);
    }
}
