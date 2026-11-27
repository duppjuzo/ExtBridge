"""Host regression tests: Python 3, JDK and Bash; never invokes a device or real su."""

import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest
import zipfile


REPO = Path(__file__).resolve().parents[1]
BASH = shutil.which("bash")
TOKEN = "test_activation_0123456789"
JAR = b"PK\x00test\narchive\xff"
MOCK = """#!/usr/bin/env bash
case "${0##*/}" in
    id) case "$1" in -u|-g) echo "${TEST_UID:-0}";; -Z) echo "$TEST_DOMAIN";; esac;;
    su)
        [[ "$1" == 2000 || "$1" == shell ]] || exit 1
        shift
        [[ "$1" != /system/bin/sh ]] || shift
        [[ "$1" == -c ]] || exit 1
        TEST_UID=2000 exec bash -c "$2";;
    runcon) [[ "$1" == u:r:shell:s0 && "$RUNCON_MODE" != denied ]] || exit 1
        [[ "$RUNCON_MODE" == unchanged ]] || export TEST_DOMAIN="$1"; shift; exec "$@";;
    app_process)
        [[ "$TEST_UID" == 2000 && "$TEST_DOMAIN" == u:r:shell:s0 ]] || exit 12
        if [[ "$3" == --verify-helper ]]; then
            echo verify >> events
            exit "${VERIFY_EXIT:-0}"
        fi
        echo start >> events
        trap 'echo stopped >> events; exit 0' TERM
        sleep 1.5 & wait $!
        echo exited >> events;;
    pm) [[ "$*" == 'path com.extbridge.helper' ]] && echo package:source.apk; exit 0;;
    content) echo replace >> events;;
    pgrep) exit 1;;
    pkill|appops|dumpsys) exit 0;;
esac
"""
EMITTER = """
import com.extbridge.helper.DaemonLaunchScript;
class Emit {
    public static void main(String[] args) {
        System.out.print("pc".equals(args[0]) ? DaemonLaunchScript.pcCommand(10)
                : DaemonLaunchScript.build(args[1], "stage.jar", args[2], 10,
                        Long.parseLong(args[3]), "root".equals(args[0]) ? "su" : null));
    }
}
"""


class ActivationShellTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not BASH:
            raise unittest.SkipTest("Bash is required")
        classes = tempfile.TemporaryDirectory()
        cls.addClassCleanup(classes.cleanup)
        cls.classes = Path(classes.name)
        emitter = cls.classes / "Emit.java"
        emitter.write_text(EMITTER, encoding="utf-8")
        subprocess.run(["javac", "-d", str(cls.classes), str(emitter),
                        str(REPO / "helper/src/com/extbridge/helper/DaemonLaunchScript.java")], check=True)

    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.cleanup_work, temp)
        self.work = Path(temp.name)
        (self.work / "sandbox").mkdir()
        (self.work / "bin").mkdir()
        for name in ("id", "su", "runcon", "app_process", "pm", "content", "pgrep", "pkill", "appops", "dumpsys"):
            path = self.work / "bin" / name
            path.write_bytes(MOCK.encode())
            path.chmod(0o700)
        (self.work / "stage.jar").write_bytes(JAR)
        self.env = {**os.environ, "BASH_ENV": "", "TEST_UID": "0", "VERIFY_EXIT": "0",
                    "TEST_DOMAIN": "u:r:test_su:s0", "RUNCON_MODE": "ok"}

    @staticmethod
    def cleanup_work(temp):
        # On Windows an exiting Bash child can briefly retain the working directory.
        for _ in range(20):
            try:
                temp.cleanup()
                return
            except PermissionError:
                time.sleep(0.1)
        temp.cleanup()

    def script(self, mode):
        return subprocess.check_output([
            "java", "-cp", str(self.classes), "Emit", mode, TOKEN, hashlib.sha256(JAR).hexdigest(), str(len(JAR)),
        ], text=True).replace("/data/local/tmp", "sandbox").replace("/system/bin/app_process", "bin/app_process").replace("/system/bin/sh", "bash")

    def deploy(self, approve_replace=False, approve_keep=False):
        (self.work / "deploy.sh").write_bytes(('export PATH="$PWD/bin:$PATH"\n' + self.script("root")).encode())
        with subprocess.Popen([BASH, "deploy.sh"], cwd=self.work, env=self.env,
                              stdin=subprocess.PIPE, stdout=subprocess.PIPE) as proc:
            seen = []
            gates = {f"EXTBRIDGE_ROOT_READY:{TOKEN}": approve_replace, f"EXTBRIDGE_ROOT_STARTED:{TOKEN}": approve_keep}
            for raw in proc.stdout:
                line = raw.decode().strip()
                seen.append(line)
                if gates.get(line):
                    proc.stdin.write(b"GO\n")
                    proc.stdin.flush()
                elif line.startswith(("EXTBRIDGE_ROOT_", "ERR_")) and not proc.stdin.closed:
                    proc.stdin.close()
            return proc.wait(timeout=5), seen

    def events(self):
        return (self.work / "events").read_text().splitlines()

    def wait_for_event(self, event):
        deadline = time.monotonic() + 5
        while event not in self.events() and time.monotonic() < deadline:
            time.sleep(0.05)
        self.assertIn(event, self.events())

    def test_cancel_before_replacement_preserves_old_service(self):
        live = self.work / "sandbox" / "extbridge.jar"
        live.write_bytes(b"old")
        code, _ = self.deploy()
        self.assertEqual(code, 125)
        self.assertEqual(live.read_bytes(), b"old")
        self.assertEqual(self.events(), ["verify"])

    def test_cancel_after_launch_terminates_new_process(self):
        code, output = self.deploy(approve_replace=True)
        self.assertEqual(code, 125)
        self.assertNotIn("EXTBRIDGE_LAUNCH:", "\n".join(output))
        self.assertEqual(self.events()[:3], ["verify", "replace", "start"])
        self.wait_for_event("stopped")

    def test_fallback_apk_and_unusable_log_do_not_block_launch(self):
        (self.work / "stage.jar").unlink()
        with zipfile.ZipFile(self.work / "source.apk", "w") as apk:
            apk.writestr("assets/extbridge.jar", JAR)
        (self.work / "sandbox" / "extbridge.log").mkdir()
        code, output = self.deploy(approve_replace=True, approve_keep=True)
        self.assertEqual(code, 0, output)
        self.assertRegex(output[-1], rf"^EXTBRIDGE_LAUNCH:{TOKEN}:\d+$")
        self.wait_for_event("exited")

    def test_failed_signature_preflight_preserves_old_service(self):
        self.env["VERIFY_EXIT"] = "3"
        code, output = self.deploy(approve_replace=True)
        self.assertEqual(code, 21, output)
        self.assertIn("ERR_VERIFY_HELPER", output)
        self.assertNotIn("replace", self.events())

    def test_domain_must_be_confirmed_before_replacement(self):
        live = self.work / "sandbox" / "extbridge.jar"
        live.write_bytes(b"old")
        for mode in ("denied", "unchanged"):
            with self.subTest(mode=mode):
                self.env["RUNCON_MODE"] = mode
                code, output = self.deploy(approve_replace=True, approve_keep=True)
                self.assertEqual(code, 12, output)
                self.assertIn("ERR_SHELL_DOMAIN", output)
                self.assertFalse((self.work / "events").exists())
                self.assertEqual(live.read_bytes(), b"old")

    def test_native_shell_domain_needs_no_runcon(self):
        self.env.update(TEST_DOMAIN="u:r:shell:s0", RUNCON_MODE="denied")
        code, output = self.deploy(approve_replace=True, approve_keep=True)
        self.assertEqual(code, 0, output)
        self.assertEqual(self.events()[:3], ["verify", "replace", "start"])
        self.wait_for_event("exited")

    def test_daemon_identity_rejection_is_not_a_signature_error(self):
        self.env["VERIFY_EXIT"] = "12"
        code, output = self.deploy(approve_replace=True)
        self.assertEqual(code, 12, output)
        self.assertIn("ERR_SHELL_ID", output)
        self.assertEqual(self.events(), ["verify"])

    def test_desktop_command_stays_plain_and_portable(self):
        pc = self.script("pc")
        for forbidden in ("$", "`", "base64", "/proc/", "getDaemonStatus"):
            self.assertNotIn(forbidden, pc)
        self.assertTrue(pc.startswith('adb shell "') and pc.endswith('"'))
        subprocess.run([BASH, "-n"], input=pc[len('adb shell "'):-1].encode(), check=True)
        subprocess.run([BASH, "-n"], input=self.script("wireless").encode(), check=True)


if __name__ == "__main__":
    unittest.main()
