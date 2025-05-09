package com.github.blueboytm.flutter_v2ray.v2ray.services;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import com.github.blueboytm.flutter_v2ray.v2ray.V2rayReceiver;
import com.github.blueboytm.flutter_v2ray.v2ray.core.V2rayCoreManager;
import com.github.blueboytm.flutter_v2ray.v2ray.interfaces.V2rayServicesListener;
import com.github.blueboytm.flutter_v2ray.v2ray.utils.AppConfigs;
import com.github.blueboytm.flutter_v2ray.v2ray.utils.V2rayConfig;

import java.util.ArrayList;

public class V2rayProxyOnlyService extends Service implements V2rayServicesListener {

    private final BroadcastReceiver v2rayReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                ArrayList<String> list = new ArrayList<>();
                list.add(intent.getStringExtra("DURATION"));
                list.add(String.valueOf(intent.getLongExtra("UPLOAD_SPEED", 0)));
                list.add(String.valueOf(intent.getLongExtra("DOWNLOAD_SPEED", 0)));
                list.add(String.valueOf(intent.getLongExtra("UPLOAD_TRAFFIC", 0)));
                list.add(String.valueOf(intent.getLongExtra("DOWNLOAD_TRAFFIC", 0)));
                list.add(intent.getSerializableExtra("STATE").toString().substring(6));
                V2rayReceiver.vpnStatusSink.success(list);
                AppConfigs.V2RAY_STATE = (AppConfigs.V2RAY_STATES) intent.getSerializableExtra("STATE");
                //Log.d("V2rayProxyOnlyService", "Received broadcast: " + intent.getAction());
            } catch (Exception e) {
                Log.e("V2rayProxyOnlyService", "Broadcast receive failed", e);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        V2rayCoreManager.getInstance().setUpListener(this);

        // Register receiver dynamically
        IntentFilter filter = new IntentFilter("V2RAY_CONNECTION_INFO");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(v2rayReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(v2rayReceiver, filter);
        }
        //Log.d("V2rayProxyOnlyService", "Registered V2rayReceiver");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AppConfigs.V2RAY_SERVICE_COMMANDS startCommand = (AppConfigs.V2RAY_SERVICE_COMMANDS) intent.getSerializableExtra("COMMAND");
        if (startCommand.equals(AppConfigs.V2RAY_SERVICE_COMMANDS.START_SERVICE)) {
            V2rayConfig v2rayConfig = (V2rayConfig) intent.getSerializableExtra("V2RAY_CONFIG");
            if (v2rayConfig == null) {
                this.onDestroy();
            }
            if (V2rayCoreManager.getInstance().isV2rayCoreRunning()) {
                V2rayCoreManager.getInstance().stopCore(true);
            }
            assert v2rayConfig != null;
            if (V2rayCoreManager.getInstance().startCore(v2rayConfig, true)) {
                Log.e(V2rayProxyOnlyService.class.getSimpleName(), "onStartCommand success => v2ray core started.");
            } else {
                this.onDestroy();
            }
        } else if (startCommand.equals(AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE)) {
            V2rayCoreManager.getInstance().stopCore(true);
            AppConfigs.V2RAY_CONFIG = null;
        } else if (startCommand.equals(AppConfigs.V2RAY_SERVICE_COMMANDS.MEASURE_DELAY)) {
            new Thread(() -> {
                Intent sendB = new Intent("CONNECTED_V2RAY_SERVER_DELAY");
                sendB.putExtra("DELAY", String.valueOf(V2rayCoreManager.getInstance().getConnectedV2rayServerDelay()));
                sendBroadcast(sendB);
            }, "MEASURE_CONNECTED_V2RAY_SERVER_DELAY").start();
        } else {
            this.onDestroy();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        // Unregister receiver
        try {
            unregisterReceiver(v2rayReceiver);
            //Log.d("V2rayProxyOnlyService", "Unregistered V2rayReceiver");
        } catch (Exception e) {
            Log.e("V2rayProxyOnlyService", "Failed to unregister receiver", e);
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public boolean onProtect(int socket) {
        return true;
    }

    @Override
    public Service getService() {
        return this;
    }

    @Override
    public void startService() {
        //ignore
    }

    @Override
    public void stopService() {
        try {
            stopSelf();
        } catch (Exception e) {
            //ignore
        }
    }
}