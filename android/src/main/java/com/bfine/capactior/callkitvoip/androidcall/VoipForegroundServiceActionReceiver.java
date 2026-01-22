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

            if (action != null) {
                performClickAction(context, action, connectionId);
            }
        }
    }

    private void performClickAction(Context context, String action, String connectionId) {
        Log.d(TAG, "action: " + action + ", connectionId: " + connectionId);

        if (action.equals("CANCEL_CALL")) {
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
            
            endCall(connectionId);
        }
    }

    public void endCall(String connectionId) {
        Log.d(TAG, "endCall for connectionId: " + connectionId);
        
        CallKitVoipPlugin instance = CallKitVoipPlugin.getInstance();
        if (instance != null) {
            instance.notifyEvent("callRejected", connectionId);
        } else {
            Log.e(TAG, "CallKitVoipPlugin instance is null, cannot notify call rejected");
        }
    }
}
