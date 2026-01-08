package com.bfine.capactior.callkitvoip.androidcall;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.telecom.Connection;
import android.telecom.DisconnectCause;
import android.util.Log;

import com.bfine.capactior.callkitvoip.CallKitVoipPlugin;
import com.bfine.capactior.callkitvoip.MyConnectionService;

public class VoipForegroundServiceActionReceiver extends BroadcastReceiver {
    private static final String TAG = "VoipActionReceiver";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null) {
            String action = intent.getAction();
            String connectionId = intent.getStringExtra("connectionId");
            String username = intent.getStringExtra("username");
            String from = intent.getStringExtra("from");

            if (action != null) {
                performClickAction(context, action, username, connectionId);
            }
        }
    }

    private void performClickAction(Context context, String action, String username, String connectionId) {
        Log.d(TAG, "action: " + action + ", username: " + username + ", connectionId: " + connectionId);

        if (action.equals("RECEIVE_CALL")) {
            context.stopService(new Intent(context, VoipForegroundService.class));
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Connection connection = MyConnectionService.getConnection();
                if (connection != null) {
                    connection.setActive();
                    Log.d(TAG, "Connection set to ACTIVE via notification answer button");
                } else {
                    Log.w(TAG, "Connection is null, cannot set active");
                }
            }
            
            launchApp(context, connectionId, username);
            
            CallKitVoipPlugin instance = CallKitVoipPlugin.getInstance();
            if (instance != null) {
                instance.notifyEvent("callAnswered", connectionId);
            } else {
                Log.e(TAG, "CallKitVoipPlugin instance is null, cannot notify call answered");
            }
        } else if (action.equals("FULLSCREEN_CALL")) {
            context.stopService(new Intent(context, VoipForegroundService.class));
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Connection connection = MyConnectionService.getConnection();
                if (connection != null) {
                    connection.setActive();
                    Log.d(TAG, "Connection set to ACTIVE via fullscreen button");
                } else {
                    Log.w(TAG, "Connection is null, cannot set active");
                }
            }
            
            launchApp(context, connectionId, username);
            
            CallKitVoipPlugin instance = CallKitVoipPlugin.getInstance();
            if (instance != null) {
                instance.notifyEvent("callAnswered", connectionId);
            } else {
                Log.e(TAG, "CallKitVoipPlugin instance is null, cannot notify call answered");
            }
        } else if (action.equals("CANCEL_CALL")) {
            context.stopService(new Intent(context, VoipForegroundService.class));
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Connection connection = MyConnectionService.getConnection();
                if (connection != null) {
                    DisconnectCause cause = new DisconnectCause(DisconnectCause.REJECTED);
                    connection.setDisconnected(cause);
                    connection.destroy();
                    MyConnectionService.deinitConnection();
                    Log.d(TAG, "Connection rejected and destroyed via notification reject button");
                } else {
                    Log.w(TAG, "Connection is null, cannot reject");
                }
            }
            
            endCall(username, connectionId);
        }
    }
    
    private void launchApp(Context context, String connectionId, String username) {
        try {
            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | 
                                     Intent.FLAG_ACTIVITY_CLEAR_TOP | 
                                     Intent.FLAG_ACTIVITY_SINGLE_TOP);
                launchIntent.putExtra("connectionId", connectionId);
                launchIntent.putExtra("username", username);
                launchIntent.putExtra("isIncomingCall", true);
                launchIntent.putExtra("callAnswered", true);
                context.startActivity(launchIntent);
                Log.d(TAG, "Launched app for incoming call");
            } else {
                Log.e(TAG, "Cannot get launch intent for package");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error launching app", e);
        }
    }

    public void endCall(String username, String connectionId) {
        Log.d(TAG, "endCall for username: " + username + ", connectionId: " + connectionId);
        
        CallKitVoipPlugin instance = CallKitVoipPlugin.getInstance();
        if (instance != null) {
            instance.notifyEvent("callRejected", connectionId);
        } else {
            Log.e(TAG, "CallKitVoipPlugin instance is null, cannot notify call rejected");
        }
    }
}
