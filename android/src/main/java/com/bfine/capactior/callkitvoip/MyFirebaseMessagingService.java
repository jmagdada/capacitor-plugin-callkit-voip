package com.bfine.capactior.callkitvoip;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;

import com.bfine.capactior.callkitvoip.androidcall.VoipForegroundService;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import androidx.annotation.RequiresApi;

import java.util.Map;
import java.util.UUID;

@SuppressLint("MissingFirebaseInstanceTokenRefresh")
public class MyFirebaseMessagingService extends FirebaseMessagingService {

    private static final String TAG = "MyFirebaseMsgService";

    public MyFirebaseMessagingService() {
        super();
        Log.d(TAG, "class instantiated");
    }

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        Log.d(TAG, "received " + remoteMessage.getData());

        if (remoteMessage.getData().containsKey("type") && 
            remoteMessage.getData().get("type").equals("call")) {
            
            String connectionId = remoteMessage.getData().get("connectionId");
            String callId = remoteMessage.getData().get("callId");
            String media = remoteMessage.getData().get("media");
            String duration = remoteMessage.getData().get("duration");
            String bookingIdStr = remoteMessage.getData().get("bookingId");
            String host = remoteMessage.getData().get("host");
            String username = remoteMessage.getData().get("username");
            String secret = remoteMessage.getData().get("secret");
            String fromNumber = remoteMessage.getData().get("from");
            
            if (fromNumber == null || fromNumber.isEmpty()) {
                fromNumber = remoteMessage.getData().get("name");
            }
            
            if (connectionId == null || connectionId.isEmpty()) {
                connectionId = UUID.randomUUID().toString();
                Log.d(TAG, "Generated connectionId: " + connectionId);
            }
            
            if (callId == null || callId.isEmpty()) {
                callId = connectionId;
            }
            if (media == null) {
                media = "audio";
            }
            if (duration == null) {
                duration = "0";
            }
            int bookingId = 0;
            try {
                if (bookingIdStr != null) {
                    bookingId = Integer.parseInt(bookingIdStr);
                }
            } catch (NumberFormatException e) {
                Log.e(TAG, "Invalid bookingId format: " + bookingIdStr);
            }
            if (host == null) {
                host = "";
            }
            if (username == null) {
                username = "Unknown";
            }
            if (secret == null) {
                secret = "";
            }
            
            CallConfig config = new CallConfig(
                callId,
                media,
                duration,
                bookingId,
                host,
                username,
                secret
            );
            
            CallKitVoipPlugin.storeCallConfig(connectionId, config);
            CallStateManager.saveCallState(getApplicationContext(), connectionId, config);
            CallQualityMonitor.trackCallStart(connectionId);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (PhoneAccountHelper.isPhoneAccountEnabled(getApplicationContext())) {
                    showNativeIncomingCall(connectionId, username, fromNumber);
                } else {
                    Log.w(TAG, "PhoneAccount not enabled, using notification fallback");
                    CallKitVoipPlugin plugin = CallKitVoipPlugin.getInstance();
                    if (plugin != null) {
                        plugin.notifyError(CallKitError.PHONE_ACCOUNT_DISABLED, 
                            "PhoneAccount is not enabled. Using notification UI instead.");
                    }
                    showNotificationIncomingCall(connectionId, username, fromNumber);
                }
            } else {
                showNotificationIncomingCall(connectionId, username, fromNumber);
            }
        }

        if (remoteMessage.getData().containsKey("type") && 
            remoteMessage.getData().get("type").equals("stopCall")) {
            endCall();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void showNativeIncomingCall(String connectionId, String username, String fromNumber) {
        try {
            TelecomManager telecomManager = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
            
            if (telecomManager == null) {
                Log.e(TAG, "TelecomManager is null, falling back to notification");
                showNotificationIncomingCall(connectionId, username, fromNumber);
                return;
            }

            PhoneAccountHandle phoneAccountHandle = CallKitVoipPlugin.getPhoneAccountHandle();
            
            if (phoneAccountHandle == null) {
                Log.e(TAG, "PhoneAccountHandle is not registered, falling back to notification");
                showNotificationIncomingCall(connectionId, username, fromNumber);
                return;
            }

            String addressString = username != null ? username : (fromNumber != null ? fromNumber : "unknown");
            if (addressString.contains(" ") || !addressString.matches("^[a-zA-Z0-9@._-]+$")) {
                addressString = username != null ? username : "unknown";
            }
            Uri addressUri;
            if (addressString.startsWith("tel:") || addressString.startsWith("sip:")) {
                addressUri = Uri.parse(addressString);
            } else {
                addressUri = Uri.fromParts("sip", addressString, null);
            }

            Bundle extras = new Bundle();
            extras.putString("connectionId", connectionId);
            extras.putString("username", username);
            extras.putString("from", fromNumber != null ? fromNumber : username);
            extras.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, addressUri);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                extras.putBoolean(PhoneAccount.EXTRA_ADD_SELF_MANAGED_CALLS_TO_INCALLSERVICE, true);
            }

            Log.d(TAG, "Calling addNewIncomingCall with connectionId: " + connectionId + ", username: " + username + ", from: " + fromNumber + ", addressUri: " + addressUri);
            telecomManager.addNewIncomingCall(phoneAccountHandle, extras);
            
            Log.d(TAG, "Incoming call shown: " + username);
            
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException: Falling back to notification UI. Error: " + e.getMessage());
            CallQualityMonitor.trackCallFailure(connectionId, "SecurityException: " + e.getMessage());
            CallKitVoipPlugin plugin = CallKitVoipPlugin.getInstance();
            if (plugin != null) {
                plugin.notifyError(CallKitError.PERMISSION_DENIED, 
                    "Permission denied for native call UI. Using notification UI instead.");
            }
            showNotificationIncomingCall(connectionId, username, fromNumber);
        } catch (Exception e) {
            Log.e(TAG, "Error showing native incoming call, falling back to notification", e);
            CallQualityMonitor.trackCallFailure(connectionId, "Exception: " + e.getMessage());
            CallKitVoipPlugin plugin = CallKitVoipPlugin.getInstance();
            if (plugin != null) {
                plugin.notifyError(CallKitError.CONNECTION_FAILED, 
                    "Failed to show native call UI: " + e.getMessage());
            }
            showNotificationIncomingCall(connectionId, username, fromNumber);
        }
    }

    private void showNotificationIncomingCall(String connectionId, String username, String fromNumber) {
        try {
            Intent serviceIntent = new Intent(this, VoipForegroundService.class);
            serviceIntent.setAction("incoming");
            serviceIntent.putExtra("connectionId", connectionId);
            serviceIntent.putExtra("username", username);
            serviceIntent.putExtra("from", fromNumber != null ? fromNumber : username);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            
            Log.d(TAG, "Notification incoming call service started for: " + username);
        } catch (Exception e) {
            Log.e(TAG, "Error showing notification incoming call", e);
        }
    }

    private void endCall() {
        CallKitVoipPlugin plugin = CallKitVoipPlugin.getInstance();
        if (plugin != null) {
            Map<String, CallConfig> allConfigs = CallKitVoipPlugin.getAllCallConfigs();
            for (String connectionId : allConfigs.keySet()) {
                CallQualityMonitor.trackCallEnd(connectionId, "Remote hangup");
                plugin.notifyEvent("callEnded", connectionId);
                CallKitVoipPlugin.removeCallConfig(connectionId);
                CallStateManager.clearCallState(getApplicationContext(), connectionId);
                CallQualityMonitor.clearMetrics(connectionId);
            }
        }
    }

    @Override
    public void onNewToken(String token) {
        Log.d(TAG, "Refreshed token: " + token);
        CallKitVoipPlugin plugin = CallKitVoipPlugin.getInstance();
        if (plugin != null) {
            plugin.notifyRegistration(token);
        }
    }
}
