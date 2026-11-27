# The shell daemon looks up the client provider and entry points by name.
-keep class com.extbridge.helper.BridgeProvider { *; }
-keep class com.extbridge.helper.ControlReceiver { *; }
-keep class com.extbridge.helper.NavAssistService { *; }
-keep class com.extbridge.helper.AdbPairingReceiver { *; }
-keep class com.extbridge.helper.BridgeService { public *; }
-keep class com.extbridge.helper.MainActivity { public *; }
-keep class com.extbridge.helper.TouchpadActivity { public *; }
-keep class com.extbridge.helper.LauncherActivity { public *; }
