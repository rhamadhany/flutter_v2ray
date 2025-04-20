package com.github.blueboytm.flutter_v2ray.v2ray;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.ArrayList;

import io.flutter.plugin.common.EventChannel;

public class V2rayReceiver extends BroadcastReceiver {
    public static EventChannel.EventSink vpnStatusSink;

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (vpnStatusSink != null && "V2RAY_CONNECTION_INFO".equals(intent.getAction())) {
                ArrayList<String> list = new ArrayList<>();
                
                // Add duration
                String duration = intent.getStringExtra("DURATION");
                list.add(duration != null ? duration : "00:00:00");
                
                // Add speeds and traffic
                list.add(String.valueOf(intent.getLongExtra("UPLOAD_SPEED", 0)));
                list.add(String.valueOf(intent.getLongExtra("DOWNLOAD_SPEED", 0)));
                list.add(String.valueOf(intent.getLongExtra("UPLOAD_TRAFFIC", 0)));
                list.add(String.valueOf(intent.getLongExtra("DOWNLOAD_TRAFFIC", 0)));
                
                // Add connection state
                Object state = intent.getSerializableExtra("STATE");
                list.add(state != null ? state.toString().substring(6) : "DISCONNECTED");
                
                vpnStatusSink.success(list);
                //Log.d("V2rayReceiver", "Status update sent to Flutter: " + list);
            }
        } catch (Exception e) {
            Log.e("V2rayReceiver", "Error processing broadcast", e);
        }
    }
}