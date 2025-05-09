package com.github.blueboytm.flutter_v2ray.v2ray.core;

import static com.github.blueboytm.flutter_v2ray.v2ray.utils.Utilities.getUserAssetsPath;

import java.util.Timer;
import java.util.TimerTask;

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
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
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

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class V2rayCoreManager {
    private static final int NOTIFICATION_ID = 1;
    private volatile static V2rayCoreManager INSTANCE;
    public V2rayServicesListener v2rayServicesListener = null;
    public final V2RayPoint v2RayPoint = Libv2ray.newV2RayPoint(new V2RayVPNServiceSupportsSet() {
        @Override
        public long shutdown() {
            if (v2rayServicesListener == null) {
                Log.e(V2rayCoreManager.class.getSimpleName(), "shutdown failed => can't find initial service.");
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
    private V2rayConfig currentV2rayConfig;
    private volatile int retryReconnect = 0;
    // private volatile int retryPing = 0;
    private Timer timerReconnect;

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
            Log.e(V2rayCoreManager.class.getSimpleName(), "setUpListener => new initialize from " + 
                v2rayServicesListener.getService().getClass().getSimpleName());
        } catch (Exception e) {
            Log.e(V2rayCoreManager.class.getSimpleName(), "setUpListener failed => ", e);
            isLibV2rayCoreInitialized = false;
        }
    }

    public boolean startCore(final V2rayConfig v2rayConfig, boolean stopTimer) {
        currentV2rayConfig = v2rayConfig;
        // Run makeDurationTimer on main thread to avoid Handler error
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            makeDurationTimer(v2rayServicesListener.getService().getApplicationContext(), v2rayConfig.ENABLE_TRAFFIC_STATICS);
        });
        V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_CONNECTING;
        
        if (!isLibV2rayCoreInitialized) {
            Log.e(V2rayCoreManager.class.getSimpleName(), "startCore failed => LibV2rayCore must be initialized first.");
            return false;
        }
        
        if (isV2rayCoreRunning()) {
            stopCore(stopTimer);
        }
        
        try {
            v2RayPoint.setConfigureFileContent(v2rayConfig.V2RAY_FULL_JSON_CONFIG);
            v2RayPoint.setDomainName(v2rayConfig.CONNECTED_V2RAY_SERVER_ADDRESS + ":" + v2rayConfig.CONNECTED_V2RAY_SERVER_PORT);
            v2RayPoint.runLoop(false);
            V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_CONNECTED;
            
            if (isV2rayCoreRunning()) {
                displayNotification(v2rayConfig, true);
            }

            // Log.d("v2rayConfig.autoReconnect "+ v2rayConfig , "tes");
            

            return true;
        } catch (Exception e) {
            Log.e(V2rayCoreManager.class.getSimpleName(), "startCore failed =>", e);
            return false;
        }
    }

    public void stopCore(boolean stopTimer) {
        try {
            if (stopTimer && timerReconnect != null) {
                timerReconnect.cancel();
                timerReconnect = null;
            }
            NotificationManager notificationManager = (NotificationManager) 
                v2rayServicesListener.getService().getSystemService(Context.NOTIFICATION_SERVICE);
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
            Context context = v2rayServicesListener.getService().getApplicationContext();
            Intent connection_info_intent = new Intent("V2RAY_CONNECTION_INFO");
            connection_info_intent.setPackage(context.getPackageName());
            connection_info_intent.putExtra("STATE", V2RAY_STATE);
            connection_info_intent.putExtra("DURATION", SERVICE_DURATION);
            connection_info_intent.putExtra("UPLOAD_SPEED", uploadSpeed);
            connection_info_intent.putExtra("DOWNLOAD_SPEED", downloadSpeed);
            connection_info_intent.putExtra("UPLOAD_TRAFFIC", totalUpload);
            connection_info_intent.putExtra("DOWNLOAD_TRAFFIC", totalDownload);
            
            try {
                context.sendBroadcast(connection_info_intent);
            } catch (Exception e) {
                Log.e("V2rayCoreManager", "Failed to send disconnected state", e);
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
            NotificationChannel channel = new NotificationChannel(channelId, channelName, 
                NotificationManager.IMPORTANCE_DEFAULT);
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
                
                SERVICE_DURATION = Utilities.convertIntToTwoDigit(hours) + ":" + 
                    Utilities.convertIntToTwoDigit(minutes) + ":" + 
                    Utilities.convertIntToTwoDigit(seconds);
                    
                Intent connection_info_intent = new Intent("V2RAY_CONNECTION_INFO");
                connection_info_intent.setPackage(context.getPackageName());
                connection_info_intent.putExtra("STATE", V2RAY_STATE);
                connection_info_intent.putExtra("DURATION", SERVICE_DURATION);
                connection_info_intent.putExtra("UPLOAD_SPEED", uploadSpeed);
                connection_info_intent.putExtra("DOWNLOAD_SPEED", downloadSpeed);
                connection_info_intent.putExtra("UPLOAD_TRAFFIC", totalUpload);
                connection_info_intent.putExtra("DOWNLOAD_TRAFFIC", totalDownload);
                
                try {
                    context.sendBroadcast(connection_info_intent);
                } catch (Exception e) {
                    Log.e("V2rayCoreManager", "Failed to send update", e);
                }
                
                if (currentV2rayConfig != null) {
                    displayNotification(currentV2rayConfig, false);
                }
            }
            
            public void onFinish() {
                countDownTimer.cancel();
                if (V2rayCoreManager.getInstance().isV2rayCoreRunning()) {
                    makeDurationTimer(context, enable_traffic_statics);
                }
            }
        }.start();
    }

    public boolean isV2rayCoreRunning() {
        return v2RayPoint != null && v2RayPoint.getIsRunning();
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
            JSONObject config_json = new JSONObject(config);
            try {
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

    private void displayNotification(final V2rayConfig v2rayConfig, boolean isStartForeground) {
        Service context = v2rayServicesListener.getService();
        if (context == null) {
            return;
        }
    
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
    
        final int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ?
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT :
                PendingIntent.FLAG_UPDATE_CURRENT;
    
        PendingIntent notificationContentPendingIntent = PendingIntent.getActivity(
                context, 0, launchIntent, flags);
    
        Intent stopIntent;
        if (AppConfigs.V2RAY_CONNECTION_MODE == AppConfigs.V2RAY_CONNECTION_MODES.PROXY_ONLY) {
            stopIntent = new Intent(context, V2rayProxyOnlyService.class);
        } else {
            stopIntent = new Intent(context, V2rayVPNService.class);
        }
        stopIntent.putExtra("COMMAND", AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE);
        PendingIntent pendingIntent = PendingIntent.getService(context, 0, stopIntent, flags);
    
        String uploadSpeedText = formatBytes(uploadSpeed);
        String downloadSpeedText = formatBytes(downloadSpeed);
        String speedText = v2rayConfig.SHOW_SPEED ? "⬆ " + uploadSpeedText + " ⬇ " + downloadSpeedText : "";
    
        String notificationChannelID = createNotificationChannelID(v2rayConfig.APPLICATION_NAME);
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
    
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (isStartForeground) {
            context.startForeground(NOTIFICATION_ID, notificationBuilder.build());
            if (v2rayConfig.autoReconnect) {
                autoReconnect(v2rayConfig);
            }
        } else if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
        }
    }

    private void autoReconnect(final V2rayConfig v2rayConfig) {
        if (v2rayConfig == null || v2rayConfig.intervalAutoReconnect <= 0 || v2rayConfig.V2RAY_FULL_JSON_CONFIG == null ||
            v2rayConfig.maxPingAutoReconnect < 0 || v2rayConfig.maxRetryAutoReconnect < 0) {
            Log.e("V2rayCoreManager", "Invalid V2rayConfig or parameters");
            if (v2rayServicesListener != null) {
                Context context = v2rayServicesListener.getService().getApplicationContext();
                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(context, "Invalid V2rayConfig or parameters", Toast.LENGTH_SHORT).show();
                });
            }
            return;
        }

        if (timerReconnect != null) {
            timerReconnect.cancel();
            timerReconnect = null;
        }

        timerReconnect = new Timer();
        timerReconnect.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    long delay = getV2rayServerDelayWithTimeout(v2rayConfig.V2RAY_FULL_JSON_CONFIG, v2rayConfig.urlAutoReconnect, 3);

                    // String message = "Reconnect attempt: reconnect=" + retryReconnect + ", delay=" + delay;
                    // Log.d("V2rayCoreManager", message);

                    int ping = 0;
                    if (delay == -1 && retryReconnect < v2rayConfig.maxRetryAutoReconnect){
                        while (ping < v2rayConfig.maxPingAutoReconnect){
                            ping++;
                            delay = getV2rayServerDelayWithTimeout(v2rayConfig.V2RAY_FULL_JSON_CONFIG, v2rayConfig.urlAutoReconnect, 3);
                            if (delay != -1){
                                break;
                            }
                            Log.d("pengujian ping"+ping, "ping");
                        } 
if (ping == v2rayConfig.maxPingAutoReconnect && delay == -1){
    
    retryReconnect++;
    if (v2rayServicesListener != null) {

        Context context = v2rayServicesListener.getService().getApplicationContext();
        new Handler(Looper.getMainLooper()).post(()-> {
            Toast.makeText(context, "Max ping reached, try "+ retryReconnect+ " reconnecting...", Toast.LENGTH_SHORT).show();
            
        });
    }
    
    stopCore(false);
    try {
        Thread.sleep(500);
    } catch (InterruptedException e) {
        Log.e("V2rayCoreManager", "Sleep interrupted", e);
        Thread.currentThread().interrupt();
    }
    startCore(v2rayConfig, false); 
}
                    } else if (delay == -1 && retryReconnect >= v2rayConfig.maxRetryAutoReconnect){
                        stopCore(true);
                    } else {
                        retryReconnect = 0;
                    }

                } catch (Exception e) {
                    String errorMessage = "Error in autoReconnect task: " + e.getMessage();
                    Log.e("V2rayCoreManager", errorMessage, e);
                    if (v2rayServicesListener != null) {
                        Context context = v2rayServicesListener.getService().getApplicationContext();
                        new Handler(Looper.getMainLooper()).post(() -> {
                            Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show();
                        });
                    }
                }
            }
        }, 0, v2rayConfig.intervalAutoReconnect * 1000L);
    }

    private long getV2rayServerDelayWithTimeout(final String config, final String url, int timeoutSeconds) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Long> future = executor.submit(new Callable<Long>() {
            @Override
            public Long call() {
                return getV2rayServerDelay(config, url);
            }
        });
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            Log.e("V2rayCoreManager", "getV2rayServerDelay timed out");
            future.cancel(true);
            return -1L;
        } catch (Exception e) {
            Log.e("V2rayCoreManager", "Exception in getV2rayServerDelayWithTimeout", e);
            return -1L;
        } finally {
            executor.shutdownNow();
        }
    }
}