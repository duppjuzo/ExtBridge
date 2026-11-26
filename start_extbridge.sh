#!/system/bin/sh
[ "$(id -u):$(id -g)" = 2000:2000 ] || { echo EXTBRIDGE_REQUIRES_SHELL; exit 12; }
export CLASSPATH=/data/local/tmp/extbridge.jar
timeout 8 app_process /data/local/tmp com.extbridge.daemon.ExtBridge --verify-helper || exit 21
pattern='^(/system/bin/)?app_process /data/local/tmp com[.]extbridge[.]daemon[.]ExtBridge'
pkill -f "$pattern" 2>/dev/null || true
sleep 0.4
if pgrep -f "$pattern" >/dev/null; then echo OLD_DAEMON_NOT_STOPPED; exit 23; fi

appops set com.extbridge.helper SYSTEM_ALERT_WINDOW allow 2>/dev/null
appops set com.extbridge.helper POST_NOTIFICATION allow 2>/dev/null
appops set com.extbridge.helper RUN_ANY_IN_BACKGROUND allow 2>/dev/null
appops set com.extbridge.helper 10021 allow 2>/dev/null
appops set com.extbridge.helper ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null
pm grant com.extbridge.helper android.permission.POST_NOTIFICATIONS 2>/dev/null
dumpsys deviceidle whitelist +com.extbridge.helper >/dev/null 2>&1

nohup app_process /data/local/tmp com.extbridge.daemon.ExtBridge >/data/local/tmp/extbridge.log 2>&1 </dev/null &
sleep 2
cat /data/local/tmp/extbridge.log
