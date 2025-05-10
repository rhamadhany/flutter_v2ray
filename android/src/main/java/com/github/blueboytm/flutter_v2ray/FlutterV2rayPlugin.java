package com.github.blueboytm.flutter_v2ray;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;

import com.github.blueboytm.flutter_v2ray.v2ray.V2rayController;
import com.github.blueboytm.flutter_v2ray.v2ray.V2rayReceiver;
import com.github.blueboytm.flutter_v2ray.v2ray.utils.AppConfigs;
import com.github.blueboytm.flutter_v2ray.v2ray.utils.V2rayConfig;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;

public class FlutterV2rayPlugin implements FlutterPlugin, ActivityAware, PluginRegistry.ActivityResultListener {

    private static final int REQUEST_CODE_VPN_PERMISSION = 24;
    private static final int REQUEST_CODE_POST_NOTIFICATIONS = 1;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private MethodChannel vpnControlMethod;
    private EventChannel vpnStatusEvent;
    private Activity activity;
    private MethodChannel.Result pendingResult;
    
    private BroadcastReceiver v2rayReceiver;
    private Context applicationContext;

    @SuppressLint("DiscouragedApi")
    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        applicationContext = binding.getApplicationContext();
        vpnControlMethod = new MethodChannel(binding.getBinaryMessenger(), "flutter_v2ray");
        vpnStatusEvent = new EventChannel(binding.getBinaryMessenger(), "flutter_v2ray/status");

        vpnStatusEvent.setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object arguments, EventChannel.EventSink events) {
                V2rayReceiver.vpnStatusSink = events;
                registerReceiver();
              
                //Log.d("FlutterV2ray", "EventChannel listener registered");
            }

            @Override
            public void onCancel(Object arguments) {
                unregisterReceiver();
                V2rayReceiver.vpnStatusSink = null;
                //Log.d("FlutterV2ray", "EventChannel listener unregistered");
            }
        });

        vpnControlMethod.setMethodCallHandler((call, result) -> {
            switch (call.method) {
                case "changeConnectionMode":
                    String mode = call.argument("mode");
                    if (mode.equals("PROXY_ONLY")) {
                        V2rayController.changeConnectionMode(AppConfigs.V2RAY_CONNECTION_MODES.PROXY_ONLY);
                    } else {
                        V2rayController.changeConnectionMode(AppConfigs.V2RAY_CONNECTION_MODES.VPN_TUN);
                    }
                    result.success(null);
                    break;
                case "startV2Ray":
                    AppConfigs.NOTIFICATION_DISCONNECT_BUTTON_NAME = call.argument("notificationDisconnectButtonName");
                    boolean wakeLock = call.argument("wakeLock");
                    boolean showSpeed = call.argument("showSpeed");
                    if (Boolean.TRUE.equals(call.argument("proxy_only"))) {
                        V2rayController.changeConnectionMode(AppConfigs.V2RAY_CONNECTION_MODES.PROXY_ONLY);
                    }
                    V2rayController.StartV2ray(binding.getApplicationContext(), call.argument("remark"), call.argument("config"), call.argument("blocked_apps"), call.argument("bypass_subnets"), wakeLock, showSpeed);
                    result.success(null);
                    break;
                case "stopV2Ray":
                    V2rayController.StopV2ray(binding.getApplicationContext());
                    result.success(null);
                    break;
                case "initializeV2Ray":
                    String iconResourceName = call.argument("notificationIconResourceName");
                    String iconResourceType = call.argument("notificationIconResourceType");
                    V2rayController.init(binding.getApplicationContext(), binding.getApplicationContext().getResources().getIdentifier(iconResourceName, iconResourceType, binding.getApplicationContext().getPackageName()), "Flutter V2ray");
                    result.success(null);
                    break;
                case "getServerDelay":
                    executor.submit(() -> {
                        try {
                            result.success(V2rayController.getV2rayServerDelay(call.argument("config"), call.argument("url")));
                        } catch (Exception e) {
                            result.success(-1);
                        }
                    });
                    break;
                case "getConnectedServerDelay":
                    executor.submit(() -> {
                        try {
                            AppConfigs.DELAY_URL = call.argument("url");
                            result.success(V2rayController.getConnectedV2rayServerDelay(binding.getApplicationContext()));
                        } catch (Exception e) {
                            result.success(-1);
                        }
                    });
                    break;
                case "getCoreVersion":
                    result.success(V2rayController.getCoreVersion());
                    break;
                case "requestPermission":
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_CODE_POST_NOTIFICATIONS);
                        }
                    }
                    final Intent request = VpnService.prepare(activity);
                    if (request != null) {
                        pendingResult = result;
                        activity.startActivityForResult(request, REQUEST_CODE_VPN_PERMISSION);
                    } else {
                        result.success(true);
                    }
                    break;
                default:
                    result.notImplemented();
                    break;
            }
        });
    }

    private void registerReceiver() {
        if (v2rayReceiver == null) {
            v2rayReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (V2rayReceiver.vpnStatusSink != null && "V2RAY_CONNECTION_INFO".equals(intent.getAction())) {
                        new V2rayReceiver().onReceive(context, intent);
                        //Log.d("FlutterV2ray", "Received status update broadcast");
                    }
                }
            };
            
            IntentFilter filter = new IntentFilter("V2RAY_CONNECTION_INFO");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                applicationContext.registerReceiver(v2rayReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                applicationContext.registerReceiver(v2rayReceiver, filter);
            }
            //Log.d("FlutterV2ray", "Broadcast receiver registered");
        }
    }

    private void unregisterReceiver() {
        if (v2rayReceiver != null) {
            try {
                applicationContext.unregisterReceiver(v2rayReceiver);
                v2rayReceiver = null;
                //Log.d("FlutterV2ray", "Broadcast receiver unregistered");
            } catch (Exception e) {
                Log.e("FlutterV2ray", "Failed to unregister receiver", e);
            }
        }
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        unregisterReceiver();
        vpnControlMethod.setMethodCallHandler(null);
        vpnStatusEvent.setStreamHandler(null);
        executor.shutdown();
        //Log.d("FlutterV2ray", "Plugin detached from engine");
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        activity = binding.getActivity();
        binding.addActivityResultListener(this);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        // No additional cleanup required
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        activity = binding.getActivity();
        binding.addActivityResultListener(this);
    }

    @Override
    public void onDetachedFromActivity() {
        // No additional cleanup required
    }

    @Override
    public boolean onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == REQUEST_CODE_VPN_PERMISSION) {
            if (resultCode == Activity.RESULT_OK) {
                pendingResult.success(true);
            } else {
                pendingResult.success(false);
            }
            pendingResult = null;
        }
        return true;
    }
}

