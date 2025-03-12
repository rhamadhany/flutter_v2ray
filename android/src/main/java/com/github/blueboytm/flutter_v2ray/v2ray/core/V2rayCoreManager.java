package com.github.blueboytm.flutter_v2ray.v2ray.core;

import static com.github.blueboytm.flutter_v2ray.v2ray.utils.Utilities.getUserAssetsPath;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.CountDownTimer;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.github.blueboytm.flutter_v2ray.v2ray.interfaces.V2rayServicesListener;
import com.github.blueboytm.flutter_v2ray.v2ray.services.V2rayProxyOnlyService;
import com.github.blueboytm.flutter_v2ray.v2ray.services.V2rayVPNService;
import com.github.blueboytm.flutter_v2ray.v2ray.utils.AppConfigs;
import com.github.blueboytm.flutter_v2ray.v2ray.utils.Utilities;
import com.github.blueboytm.flutter_v2ray.v2ray.utils.V2rayConfig;

import org.json.JSONObject;

import libv2ray.Libv2ray;
import libv2ray.V2RayPoint;
import libv2ray.V2RayVPNServiceSupportsSet;


public final class V2rayCoreManager {
    private static final int NOTIFICATION_ID = 1;
    private volatile static V2rayCoreManager INSTANCE;
    public V2rayServicesListener v2rayServicesListener = null;
    public final V2RayPoint v2RayPoint = Libv2ray.newV2RayPoint(new V2RayVPNServiceSupportsSet() {
        @Override
        public long shutdown() {
            if (v2rayServicesListener == null) {
                Log.e(V2rayCoreManager.class.getSimpleName(), "shutdown failed => can`t find initial service.");
                return -1;
            }
            try {
                v2rayServicesListener.stopService();
                v2rayServicesListener = null;
                return 0;
            } catch (Exception e) {
                Log.e(V2rayCoreManager.class.getSimpleName(), "shutdown failed =>", e);
                return -1;
            }
        }

        @Override
        public long prepare() {
            return 0;
        }

        @Override
        public boolean protect(long l) {
            if (v2rayServicesListener != null)
                return v2rayServicesListener.onProtect((int) l);
            return true;
        }

        @Override
        public long onEmitStatus(long l, String s) {
            return 0;
        }

        @Override
        public long setup(String s) {
            if (v2rayServicesListener != null) {
                try {
                    v2rayServicesListener.startService();
                } catch (Exception e) {
                    Log.e(V2rayCoreManager.class.getSimpleName(), "setup failed => ", e);
                    return -1;
                }
            }
            return 0;
        }
    }, Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1);
    public AppConfigs.V2RAY_STATES V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_DISCONNECTED;
    private boolean isLibV2rayCoreInitialized = false;
    private CountDownTimer countDownTimer;
    private int seconds, minutes, hours;
    private long totalDownload, totalUpload, uploadSpeed, downloadSpeed;
    private String SERVICE_DURATION = "00:00:00";
    
    // Simpan konfigurasi untuk update notifikasi
    private V2rayConfig currentV2rayConfig;

    public static V2rayCoreManager getInstance() {
        if (INSTANCE == null) {
            synchronized (V2rayCoreManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new V2rayCoreManager();
                }
            }
        }
        return INSTANCE;
    }

    // Fungsi untuk format bytes
    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B/s";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB/s", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB/s", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB/s", bytes / (1024.0 * 1024 * 1024));
        }
    }

    public void setUpListener(Service targetService) {
        try {
            v2rayServicesListener = (V2rayServicesListener) targetService;
            Libv2ray.initV2Env(getUserAssetsPath(targetService.getApplicationContext()), "");
            isLibV2rayCoreInitialized = true;
            SERVICE_DURATION = "00:00:00";
            seconds = 0;
            minutes = 0;
            hours = 0;
            uploadSpeed = 0;
            downloadSpeed = 0;
            Log.e(V2rayCoreManager.class.getSimpleName(), "setUpListener => new initialize from " + v2rayServicesListener.getService().getClass().getSimpleName());
        } catch (Exception e) {
            Log.e(V2rayCoreManager.class.getSimpleName(), "setUpListener failed => ", e);
            isLibV2rayCoreInitialized = false;
        }
    }

    public boolean startCore(final V2rayConfig v2rayConfig) {
        // Simpan konfigurasi agar bisa digunakan untuk update notifikasi
        currentV2rayConfig = v2rayConfig;
        makeDurationTimer(v2rayServicesListener.getService().getApplicationContext(), v2rayConfig.ENABLE_TRAFFIC_STATICS);
        V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_CONNECTING;
        if (!isLibV2rayCoreInitialized) {
            Log.e(V2rayCoreManager.class.getSimpleName(), "startCore failed => LibV2rayCore harus diinisialisasi terlebih dahulu.");
            return false;
        }
        if (isV2rayCoreRunning()) {
            stopCore();
        }
        try {
            v2RayPoint.setConfigureFileContent(v2rayConfig.V2RAY_FULL_JSON_CONFIG);
            v2RayPoint.setDomainName(v2rayConfig.CONNECTED_V2RAY_SERVER_ADDRESS + ":" + v2rayConfig.CONNECTED_V2RAY_SERVER_PORT);
            v2RayPoint.runLoop(false);
            V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_CONNECTED;
            if (isV2rayCoreRunning()) {
                showNotification(v2rayConfig); // Buat notifikasi awal
            }
        } catch (Exception e) {
            Log.e(V2rayCoreManager.class.getSimpleName(), "startCore failed =>", e);
            return false;
        }
        return true;
    }

    public void stopCore() {
        try {
            NotificationManager notificationManager = (NotificationManager) v2rayServicesListener.getService().getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.cancel(NOTIFICATION_ID);
            }
            if (isV2rayCoreRunning()) {
                v2RayPoint.stopLoop();
                v2rayServicesListener.stopService();
                Log.e(V2rayCoreManager.class.getSimpleName(), "stopCore success => v2ray core stopped.");
            } else {
                Log.e(V2rayCoreManager.class.getSimpleName(), "stopCore failed => v2ray core not running.");
            }
            sendDisconnectedBroadCast();
        } catch (Exception e) {
            Log.e(V2rayCoreManager.class.getSimpleName(), "stopCore failed =>", e);
        }
    }

    private void sendDisconnectedBroadCast() {
        V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_DISCONNECTED;
        SERVICE_DURATION = "00:00:00";
        seconds = 0;
        minutes = 0;
        hours = 0;
        uploadSpeed = 0;
        downloadSpeed = 0;
        if (v2rayServicesListener != null) {
            Intent connection_info_intent = new Intent("V2RAY_CONNECTION_INFO");
            connection_info_intent.putExtra("STATE", V2rayCoreManager.getInstance().V2RAY_STATE);
            connection_info_intent.putExtra("DURATION", SERVICE_DURATION);
            connection_info_intent.putExtra("UPLOAD_SPEED", uploadSpeed);
            connection_info_intent.putExtra("DOWNLOAD_SPEED", downloadSpeed);            
            connection_info_intent.putExtra("UPLOAD_TRAFFIC", uploadSpeed);
            connection_info_intent.putExtra("DOWNLOAD_TRAFFIC", uploadSpeed);
            try {
                v2rayServicesListener.getService().getApplicationContext().sendBroadcast(connection_info_intent);
            } catch (Exception e) {
                // abaikan
            }
        }
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
    }

    private String createNotificationChannelID(String appName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager =
                    (NotificationManager) v2rayServicesListener.getService().getSystemService(Context.NOTIFICATION_SERVICE);
            String channelId = "A_FLUTTER_V2RAY_SERVICE_CH_ID";
            String channelName = appName + " Background Service";
            NotificationChannel channel = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(channelName);
            channel.setLightColor(Color.DKGRAY);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
            return channelId;
        }
        return "";
    }

    private void showNotification(final V2rayConfig v2rayConfig) {
        Service context = v2rayServicesListener.getService();
        if (context == null) {
            return;
        }
        // Cek permission notifikasi untuk Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }
        
        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        launchIntent.setAction(Intent.ACTION_MAIN);
        launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        final int flags;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        } else {
            flags = PendingIntent.FLAG_UPDATE_CURRENT;
        }
        PendingIntent notificationContentPendingIntent = PendingIntent.getActivity(
                context, 0, launchIntent, flags);
        String notificationChannelID = createNotificationChannelID(v2rayConfig.APPLICATION_NAME);
        Intent stopIntent;
        if (AppConfigs.V2RAY_CONNECTION_MODE == AppConfigs.V2RAY_CONNECTION_MODES.PROXY_ONLY) {
            stopIntent = new Intent(context, V2rayProxyOnlyService.class);
        } else if (AppConfigs.V2RAY_CONNECTION_MODE == AppConfigs.V2RAY_CONNECTION_MODES.VPN_TUN) {
            stopIntent = new Intent(context, V2rayVPNService.class);
        } else {
            return;
        }
        stopIntent.putExtra("COMMAND", AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE);
        PendingIntent pendingIntent = PendingIntent.getService(
                context, 0, stopIntent, flags);
        // Gunakan formatBytes untuk menampilkan kecepatan
        String uploadSpeedText = formatBytes(uploadSpeed);
        String downloadSpeedText = formatBytes(downloadSpeed);
        String speedText = v2rayConfig.SHOW_SPEED ? "⬆ " + uploadSpeedText + " ⬇ " + downloadSpeedText : "";
        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(context, notificationChannelID)
                .setSmallIcon(v2rayConfig.APPLICATION_ICON)
                .setContentTitle(v2rayConfig.REMARK)
                .setContentText(speedText)
                .addAction(0, v2rayConfig.NOTIFICATION_DISCONNECT_BUTTON_NAME, pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setContentIntent(notificationContentPendingIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true);
        context.startForeground(NOTIFICATION_ID, notificationBuilder.build());
    }

    // Method untuk update notifikasi dengan data terbaru
    private void updateNotification(V2rayConfig v2rayConfig) {
        Service context = v2rayServicesListener.getService();
        if (context == null) return;
        final int flags;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        } else {
            flags = PendingIntent.FLAG_UPDATE_CURRENT;
        }
        
        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        launchIntent.setAction(Intent.ACTION_MAIN);
        launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

        PendingIntent notificationContentPendingIntent = PendingIntent.getActivity(
                context, 0, launchIntent, flags);
        String notificationChannelID = createNotificationChannelID(v2rayConfig.APPLICATION_NAME);
        Intent stopIntent;
        if (AppConfigs.V2RAY_CONNECTION_MODE == AppConfigs.V2RAY_CONNECTION_MODES.PROXY_ONLY) {
            stopIntent = new Intent(context, V2rayProxyOnlyService.class);
        } else if (AppConfigs.V2RAY_CONNECTION_MODE == AppConfigs.V2RAY_CONNECTION_MODES.VPN_TUN) {
            stopIntent = new Intent(context, V2rayVPNService.class);
        } else {
            return;
        }
        stopIntent.putExtra("COMMAND", AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE);
        PendingIntent pendingIntent = PendingIntent.getService(
                context, 0, stopIntent, flags);
        String uploadSpeedText = formatBytes(uploadSpeed);
        String downloadSpeedText = formatBytes(downloadSpeed);
        String speedText = v2rayConfig.SHOW_SPEED ? "⬆ " + uploadSpeedText + " ⬇ " + downloadSpeedText : "";
        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(context, notificationChannelID)
                .setSmallIcon(v2rayConfig.APPLICATION_ICON)
                .setContentTitle(v2rayConfig.REMARK)
                .setContentText(speedText)
                .addAction(0, v2rayConfig.NOTIFICATION_DISCONNECT_BUTTON_NAME, pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setContentIntent(notificationContentPendingIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true);
        Notification notification = notificationBuilder.build();
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, notification);
        }
    }

    // Timer untuk menghitung durasi dan memperbarui notifikasi setiap detik
    private void makeDurationTimer(final Context context, final boolean enable_traffic_statics) {
        countDownTimer = new CountDownTimer(7200, 1000) {
            @RequiresApi(api = Build.VERSION_CODES.M)
            public void onTick(long millisUntilFinished) {
                seconds++;
                if (seconds == 59) {
                    minutes++;
                    seconds = 0;
                }
                if (minutes == 59) {
                    minutes = 0;
                    hours++;
                }
                if (hours == 23) {
                    hours = 0;
                }
                if (enable_traffic_statics) {
                    downloadSpeed = v2RayPoint.queryStats("block", "downlink") + v2RayPoint.queryStats("proxy", "downlink");
                    uploadSpeed = v2RayPoint.queryStats("block", "uplink") + v2RayPoint.queryStats("proxy", "uplink");
                    totalDownload += downloadSpeed;
                    totalUpload += uploadSpeed;
                }
                SERVICE_DURATION = Utilities.convertIntToTwoDigit(hours) + ":" + Utilities.convertIntToTwoDigit(minutes) + ":" + Utilities.convertIntToTwoDigit(seconds);
                Intent connection_info_intent = new Intent("V2RAY_CONNECTION_INFO");
                connection_info_intent.putExtra("STATE", V2rayCoreManager.getInstance().V2RAY_STATE);
                connection_info_intent.putExtra("DURATION", SERVICE_DURATION);
                // Di dalam method makeDurationTimer()
                connection_info_intent.putExtra("UPLOAD_SPEED", uploadSpeed);
                connection_info_intent.putExtra("DOWNLOAD_SPEED", downloadSpeed);
                connection_info_intent.putExtra("UPLOAD_TRAFFIC", totalUpload);
                connection_info_intent.putExtra("DOWNLOAD_TRAFFIC", totalDownload);
                context.sendBroadcast(connection_info_intent);
                if (currentV2rayConfig != null) {
                    updateNotification(currentV2rayConfig);
                }
            }
            public void onFinish() {
                countDownTimer.cancel();
                if (V2rayCoreManager.getInstance().isV2rayCoreRunning())
                    makeDurationTimer(context, enable_traffic_statics);
            }
        }.start();
    }

    public boolean isV2rayCoreRunning() {
        if (v2RayPoint != null) {
            return v2RayPoint.getIsRunning();
        }
        return false;
    }

    public Long getConnectedV2rayServerDelay() {
        try {
            return v2RayPoint.measureDelay(AppConfigs.DELAY_URL);
        } catch (Exception e) {
            return -1L;
        }
    }

    public Long getV2rayServerDelay(final String config, final String url) {
        try {
            try {
                JSONObject config_json = new JSONObject(config);
                JSONObject new_routing_json = config_json.getJSONObject("routing");
                new_routing_json.remove("rules");
                config_json.remove("routing");
                config_json.put("routing", new_routing_json);
                return Libv2ray.measureOutboundDelay(config_json.toString(), url);
            } catch (Exception json_error) {
                Log.e("getV2rayServerDelay", json_error.toString());
                return Libv2ray.measureOutboundDelay(config, url);
            }
        } catch (Exception e) {
            Log.e("getV2rayServerDelayCore", e.toString());
            return -1L;
        }
    }
}