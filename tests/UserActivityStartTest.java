package com.extbridge.daemon;

import android.content.Intent;
import android.os.Bundle;
import java.lang.reflect.Method;
import java.util.Arrays;

/** The Android types are signature tokens only; no Android framework operation runs here. */
public class UserActivityStartTest {
    public static class Legacy {
        int user = -1;
        public int startActivityAsUser(Object caller, String pkg, Intent i, String type, Object to,
                String who, int req, int flags, Object profile, Bundle opts, int target) { user = target; return 0; }
    }
    public static class Modern extends Legacy {
        public int startActivityAsUser(Object caller, String pkg, String feature, Intent i, String type, Object to,
                String who, int req, int flags, Object profile, Bundle opts, int target) { user = target; return 0; }
    }
    public static class ImplicitOnly {
        public int startActivity(Object caller, String pkg, String feature, Intent i, String type, Object to,
                String who, int req, int flags, Object profile, Bundle opts) { throw new AssertionError(); }
    }
    public static void main(String[] args) throws Exception {
        for (Legacy atm : new Legacy[]{new Legacy(), new Modern()}) {
            Method m = ExtBridge.UserActivityStart.resolve(atm.getClass());
            if (m == null || m.getDeclaringClass() != atm.getClass()) throw new AssertionError("wrong overload");
            if (!ExtBridge.UserActivityStart.invoke(m, atm, null, null, 999) || atm.user != 999) throw new AssertionError("wrong user");
        }
        if (ExtBridge.UserActivityStart.resolve(ImplicitOnly.class) != null) throw new AssertionError("implicit user fallback");
        if (!ExtBridge.UserActivityStart.amCommand(999, 7, 1).equals(Arrays.asList(
                "am", "start", "--user", "999", "--display", "7", "--windowingMode", "1"))) throw new AssertionError("CLI routing");
    }
}
