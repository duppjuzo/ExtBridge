"""Run the state-machine and profile-routing tests on the host JVM."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

TESTS = Path(__file__).resolve().parent
ROOT = TESTS.parent


class HostTest(unittest.TestCase):
    def test_wireless_transfers(self):
        helper = ROOT / 'helper/src/com/extbridge/helper'
        with tempfile.TemporaryDirectory() as out:
            subprocess.run(['javac', '-encoding', 'UTF-8', '-d', out, TESTS / 'WirelessFlowTest.java',
                            *(helper / (c + '.java') for c in ('ActivationFlow', 'ActivationSession', 'DaemonLaunchScript'))], check=True)
            for case in ('direct ACTIVATED push deploy remove', 'fallback ACTIVATED push stream deploy remove',
                         'cancel-after-push CANCELLED push remove', 'cancel-during-stream CANCELLED push stream remove',
                         'failed-stream FAILED push stream remove'):
                with self.subTest(case=case):
                    subprocess.run(['java', '-cp', out, 'WirelessFlowTest', *case.split()], check=True)

    def test_activity_start_as_user(self):
        sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
        jar = Path(sdk or '') / 'platforms/android-37.0/android.jar'
        if not jar.is_file():
            self.skipTest('Android SDK platform 37.0 is required')
        with tempfile.TemporaryDirectory() as out:
            subprocess.run(['javac', '-encoding', 'UTF-8', '-cp', jar, '-d', out, TESTS / 'UserActivityStartTest.java',
                            ROOT / 'bridge/src/com/extbridge/daemon/ExtBridge.java'], check=True)
            subprocess.run(['java', '-cp', out + os.pathsep + str(jar), 'com.extbridge.daemon.UserActivityStartTest'], check=True)


if __name__ == '__main__':
    unittest.main()
