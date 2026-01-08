package com.bfine.capactior.callkitvoip;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;

public class PhoneAccountHelper {
    private static final String TAG = "PhoneAccountHelper";
    
    public static boolean isPhoneAccountSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }
    
    public static boolean isSelfManagedSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }
    
    public static boolean isPhoneAccountEnabled(Context context) {
        if (!isPhoneAccountSupported()) {
            return false;
        }
        
        try {
            TelecomManager tm = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
            PhoneAccountHandle handle = CallKitVoipPlugin.getPhoneAccountHandle();
            
            if (tm == null || handle == null) {
                return false;
            }
            
            PhoneAccount account = tm.getPhoneAccount(handle);
            return account != null && account.isEnabled();
            
        } catch (SecurityException e) {
            Log.w(TAG, "Permission denied checking PhoneAccount", e);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error checking PhoneAccount", e);
            return false;
        }
    }
    
    public static void openPhoneAccountSettings(Context context) {
        try {
            Intent intent = new Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Log.d(TAG, "Opened phone account settings");
        } catch (Exception e) {
            Log.e(TAG, "Cannot open phone account settings", e);
        }
    }
}

