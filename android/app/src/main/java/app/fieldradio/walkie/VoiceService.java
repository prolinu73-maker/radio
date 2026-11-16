package app.fieldradio.walkie;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * 통신 중 계속 떠 있는 "마이크 사용 중" 서비스.
 * 이 서비스가 있으면 화면이 꺼지거나 다른 앱을 써도 안드로이드가 마이크와 앱을 멈추지 않습니다.
 */
public class VoiceService extends Service {
    static final String ACTION_START = "app.fieldradio.walkie.START";
    private static final String CHANNEL = "voice_channel";
    private static final int NOTIF_ID = 1001;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String title = "현장 무전기";
        String text = "통신 중입니다";
        if (intent != null) {
            if (intent.getStringExtra("title") != null) {
                title = intent.getStringExtra("title");
            }
            if (intent.getStringExtra("text") != null) {
                text = intent.getStringExtra("text");
            }
        }

        createChannel();
        Notification n = buildNotification(title, text);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            // 마이크 권한이 없는 등으로 시작하지 못하면 서비스를 끝냅니다
            stopSelf();
            return START_NOT_STICKY;
        }
        acquireLocks();
        return START_NOT_STICKY; // 시스템이 강제로 끈 뒤에는 다시 켜지 않음 (웹 화면 상태가 사라지므로)
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "통신 중 표시", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("무전기 통신이 켜져 있는 동안 표시됩니다");
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNotification(String title, String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent exit = new Intent(this, MainActivity.class);
        exit.putExtra("exit", true);
        exit.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent exitPi = PendingIntent.getActivity(this, 1, exit,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
            b.setPriority(Notification.PRIORITY_LOW);
        }
        b.setSmallIcon(R.drawable.ic_stat_mic)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(0, "종료", exitPi);
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fieldradio:voice");
                    wakeLock.setReferenceCounted(false);
                    wakeLock.acquire();
                }
            }
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "fieldradio:wifi");
                    wifiLock.setReferenceCounted(false);
                    wifiLock.acquire();
                }
            }
        } catch (Exception e) {
            // 잠금을 얻지 못해도 서비스는 계속 동작
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception e) {
            // 무시
        }
        wakeLock = null;
        wifiLock = null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        releaseLocks();
        stopForeground(true);
        super.onDestroy();
    }
}
