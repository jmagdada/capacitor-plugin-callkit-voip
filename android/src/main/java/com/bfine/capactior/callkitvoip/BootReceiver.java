package com.bfine.capactior.callkitvoip;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "CallKitBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "Boot event received: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || 
            Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    CallKitVoipPlugin.initializePhoneAccountIfNeeded(context);
                    Log.d(TAG, "PhoneAccount initialized after boot");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error initializing after boot", e);
            }
        }
    }
}

