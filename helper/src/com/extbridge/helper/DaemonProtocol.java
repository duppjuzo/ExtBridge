package com.extbridge.helper;

/** Read-only liveness proof and strict parsing; no Android dependencies in the validator. */
public final class DaemonProtocol {
    public static final int PROBE_TRANSACTION = 0x455842;
    public static final int PROBE_MAGIC = 0x45585442;
    public static final int PROBE_VERSION = 2;
    public static final int OWNED_SHUTDOWN_TRANSACTION = 0x455843;
    public static final String DESCRIPTOR = "com.extbridge.daemon.ExtBridge";

    private DaemonProtocol() {}

    public static boolean validProof(int magic, int version, long sentNonce, long receivedNonce, int pid, int uid) {
        return magic == PROBE_MAGIC && (version == 1 || version == PROBE_VERSION) && sentNonce == receivedNonce
                && pid > 0 && (uid == 2000 || uid == 0);
    }

    public interface Reader {
        int remaining();
        int readInt();
        String readString();
    }

    private static int integer(Reader reader) {
        if (reader.remaining() < 4) throw new IllegalArgumentException("Truncated daemon status");
        return reader.readInt();
    }

    private static String string(Reader reader) {
        if (reader.remaining() < 4) throw new IllegalArgumentException("Truncated daemon status string");
        String value = reader.readString();
        if (value == null) throw new IllegalArgumentException("Missing daemon status string");
        return value;
    }

    public static BridgeClient.Status readStatus(Reader reader) {
        if (reader.remaining() < 52) throw new IllegalArgumentException("Missing daemon status fields");
        BridgeClient.Status status = new BridgeClient.Status();
        status.extId = integer(reader);
        status.vdId = integer(reader);
        status.physW = integer(reader);
        status.physH = integer(reader);
        status.physFps = integer(reader) / 100f;
        status.virtW = integer(reader);
        status.virtH = integer(reader);
        status.virtDpi = integer(reader);
        status.virtFps = integer(reader) / 100f;
        status.inputToExt = integer(reader);
        status.cursorX = integer(reader);
        status.cursorY = integer(reader);
        status.extInputCnt = integer(reader);
        status.modesRaw = string(reader);
        status.renderMode = string(reader);
        status.mirrorMode = integer(reader);
        status.fullscreenPolicy = integer(reader);
        status.virtRot = integer(reader);
        status.injectStatus = integer(reader);
        status.splitActive = integer(reader);
        status.splitRatioPct = integer(reader);
        status.splitSlot0Pkg = string(reader);
        status.splitSlot1Pkg = string(reader);

        if (status.extId < -1 || status.vdId < -1
                || !dimension(status.physW) || !dimension(status.physH)
                || !dimension(status.virtW) || !dimension(status.virtH)
                || status.virtDpi < 80 || status.virtDpi > 640
                || !rate(status.physFps) || !rate(status.virtFps)
                || !binary(status.inputToExt) || !binary(status.mirrorMode)
                || !binary(status.fullscreenPolicy) || !binary(status.splitActive)
                // -1 means input devices have not been enumerated (normal before an external display exists).
                || status.extInputCnt < -1 || status.extInputCnt > 65535
                || status.virtRot < 0 || status.virtRot > 3
                // -2 is a reported input error, not a malformed status or a stopped daemon.
                || status.injectStatus < -2 || status.injectStatus > 1
                || status.splitRatioPct < 0 || status.splitRatioPct > 100
                || status.renderMode.isEmpty() || reader.remaining() != 0) {
            throw new IllegalArgumentException("Invalid daemon status values: extId=" + status.extId
                    + ", vdId=" + status.vdId + ", physical=" + status.physW + "x" + status.physH
                    + ", virtual=" + status.virtW + "x" + status.virtH + ", dpi=" + status.virtDpi
                    + ", inputCount=" + status.extInputCnt + ", inject=" + status.injectStatus
                    + ", rotation=" + status.virtRot + ", remaining=" + reader.remaining());
        }
        // The caller sets connected only AFTER the separate liveness proof is verified.
        return status;
    }

    private static boolean binary(int value) { return value == 0 || value == 1; }
    private static boolean dimension(int value) { return value >= 0 && value <= 32768; }
    private static boolean rate(float value) { return Float.isFinite(value) && value >= 0 && value <= 1000; }
}
