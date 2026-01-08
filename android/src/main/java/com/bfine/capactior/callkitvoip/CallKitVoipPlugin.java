package com.bfine.capactior.callkitvoip;

import com.getcapacitor.Bridge;
import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginHandle;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.firebase.messaging.FirebaseMessaging;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.HashMap;
import java.util.Map;

@CapacitorPlugin(name = "CallKitVoip")
public class CallKitVoipPlugin extends Plugin {
    public static Bridge staticBridge = null;
    private static Map<String, CallConfig> connectionIdRegistry = new HashMap<>();
    private static PhoneAccountHandle phoneAccountHandle = null;

    @Override
    public void load() {
        staticBridge = this.bridge;
        Context context = this.getActivity().getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            registerPhoneAccount(context);
        }
        restoreCallStates(context);
    }
    
    private void restoreCallStates(Context context) {
        try {
            Map<String, CallConfig> savedStates = CallStateManager.restoreCallStates(context);
            for (Map.Entry<String, CallConfig> entry : savedStates.entrySet()) {
                connectionIdRegistry.put(entry.getKey(), entry.getValue());
                Log.d("CallKitVoip", "Restored call state for connectionId: " + entry.getKey());
            }
        } catch (Exception e) {
            Log.e("CallKitVoip", "Error restoring call states", e);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void registerPhoneAccount(Context context) {
        try {
            TelecomManager telecomManager = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
            if (telecomManager == null) {
                Log.e("CallKitVoip", "TelecomManager is null");
                return;
            }

            ComponentName componentName = new ComponentName(context, MyConnectionService.class);
            phoneAccountHandle = new PhoneAccountHandle(componentName, "voip_account");

            int capabilities = PhoneAccount.CAPABILITY_SELF_MANAGED;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                capabilities |= PhoneAccount.CAPABILITY_SUPPORTS_VIDEO_CALLING;
            }

            PhoneAccount phoneAccount = PhoneAccount.builder(phoneAccountHandle, "VoIP Account")
                    .setCapabilities(capabilities)
                    .setShortDescription("VoIP Calls")
                    .setSupportedUriSchemes(java.util.Arrays.asList(PhoneAccount.SCHEME_TEL, PhoneAccount.SCHEME_SIP))
                    .build();

            telecomManager.registerPhoneAccount(phoneAccount);
            Log.d("CallKitVoip", "PhoneAccount registered successfully with SELF_MANAGED capability");
            
            PhoneAccount registeredAccount = telecomManager.getPhoneAccount(phoneAccountHandle);
            if (registeredAccount != null) {
                boolean isEnabled = registeredAccount.isEnabled();
                Log.d("CallKitVoip", "PhoneAccount enabled status: " + isEnabled);
                if (!isEnabled) {
                    Log.w("CallKitVoip", "PhoneAccount is not enabled! Please enable it in Settings > Calls > Calling accounts");
                }
            }
        } catch (Exception e) {
            Log.e("CallKitVoip", "Error registering PhoneAccount", e);
        }
    }
    
    @RequiresApi(api = Build.VERSION_CODES.M)
    public static void initializePhoneAccountIfNeeded(Context context) {
        try {
            TelecomManager telecomManager = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
            if (telecomManager == null) {
                Log.e("CallKitVoip", "TelecomManager is null");
                return;
            }

            ComponentName componentName = new ComponentName(context, MyConnectionService.class);
            phoneAccountHandle = new PhoneAccountHandle(componentName, "voip_account");

            int capabilities = PhoneAccount.CAPABILITY_SELF_MANAGED;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                capabilities |= PhoneAccount.CAPABILITY_SUPPORTS_VIDEO_CALLING;
            }

            PhoneAccount phoneAccount = PhoneAccount.builder(phoneAccountHandle, "VoIP Account")
                    .setCapabilities(capabilities)
                    .setShortDescription("VoIP Calls")
                    .setSupportedUriSchemes(java.util.Arrays.asList(PhoneAccount.SCHEME_TEL, PhoneAccount.SCHEME_SIP))
                    .build();

            telecomManager.registerPhoneAccount(phoneAccount);
            Log.d("CallKitVoip", "PhoneAccount re-registered after boot");
        } catch (Exception e) {
            Log.e("CallKitVoip", "Error re-registering PhoneAccount after boot", e);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    public static boolean isPhoneAccountEnabled(Context context) {
        try {
            TelecomManager telecomManager = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
            if (telecomManager == null || phoneAccountHandle == null) {
                return false;
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_NUMBERS) 
                        != PackageManager.PERMISSION_GRANTED) {
                    Log.w("CallKitVoip", "READ_PHONE_NUMBERS permission not granted, cannot verify PhoneAccount status. Assuming enabled but user should verify in Settings > Calls > Calling accounts");
                    try {
                        PhoneAccount account = telecomManager.getPhoneAccount(phoneAccountHandle);
                        if (account != null) {
                            boolean isEnabled = account.isEnabled();
                            Log.d("CallKitVoip", "PhoneAccount exists, enabled status (may be inaccurate without permission): " + isEnabled);
                            if (!isEnabled) {
                                Log.e("CallKitVoip", "PhoneAccount is NOT enabled! Please enable it in Settings > Calls > Calling accounts");
                            }
                            return isEnabled;
                        } else {
                            Log.e("CallKitVoip", "PhoneAccount not found! The account may not be registered properly.");
                            return false;
                        }
                    } catch (SecurityException e) {
                        Log.e("CallKitVoip", "SecurityException when checking PhoneAccount - cannot verify status. The PhoneAccount may not be enabled. Error: " + e.getMessage());
                        Log.e("CallKitVoip", "Please grant READ_PHONE_NUMBERS permission or manually verify PhoneAccount is enabled in Settings > Calls > Calling accounts");
                        return false;
                    }
                }
            }
            
            PhoneAccount account = telecomManager.getPhoneAccount(phoneAccountHandle);
            if (account == null) {
                Log.e("CallKitVoip", "PhoneAccount not found");
                return false;
            }
            boolean isEnabled = account.isEnabled();
            Log.d("CallKitVoip", "PhoneAccount enabled status: " + isEnabled);
            return isEnabled;
        } catch (SecurityException e) {
            Log.d("CallKitVoip", "SecurityException checking PhoneAccount status (permission not granted): " + e.getMessage());
            return true;
        } catch (Exception e) {
            Log.e("CallKitVoip", "Error checking PhoneAccount status", e);
            return false;
        }
    }

    public static PhoneAccountHandle getPhoneAccountHandle() {
        return phoneAccountHandle;
    }

    @PluginMethod
    public void register(PluginCall call) {
        final String topicName = call.getString("userToken");
        Log.d("CallKitVoip", "register");

        if (topicName == null) {
            call.reject("Topic name hasn't been specified correctly");
            return;
        }
        
        requestPhoneNumbersPermissionIfNeeded();
        
        FirebaseMessaging
            .getInstance()
            .getToken()
            .addOnSuccessListener(token -> {
                notifyRegistration(token);
                
                FirebaseMessaging
                    .getInstance()
                    .subscribeToTopic(topicName)
                    .addOnSuccessListener(unused -> {
                        JSObject ret = new JSObject();
                        Logger.debug("CallKit: Subscribed");
                        ret.put("message", "Subscribed to topic " + topicName);
                        call.resolve(ret);
                    })
                    .addOnFailureListener(e -> {
                        Logger.debug("CallKit: Cannot subscribe");
                        call.reject("Cant subscribe to topic " + topicName);
                    });
            })
            .addOnFailureListener(e -> {
                Logger.debug("CallKit: Cannot get token");
                call.reject("Cannot get FCM token: " + e.getMessage());
            });
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void requestPhoneNumbersPermissionIfNeeded() {
        Context context = getContext();
        if (context == null) {
            Log.w("CallKitVoip", "Context is null, cannot request permission");
            return;
        }
        
        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions = new String[]{
                android.Manifest.permission.READ_PHONE_STATE,
                android.Manifest.permission.READ_PHONE_NUMBERS
            };
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions = new String[]{
                android.Manifest.permission.READ_PHONE_STATE,
                android.Manifest.permission.READ_PHONE_NUMBERS
            };
        } else {
            permissions = new String[]{
                android.Manifest.permission.READ_PHONE_STATE
            };
        }
        
        boolean needsRequest = false;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                needsRequest = true;
                break;
            }
        }
        
        if (needsRequest) {
            Log.d("CallKitVoip", "Phone permissions not granted, requesting...");
            ActivityCompat.requestPermissions(getActivity(), permissions, 1001);
            Log.d("CallKitVoip", "Permission request sent for phone permissions");
        } else {
            Log.d("CallKitVoip", "Phone permissions already granted");
        }
    }

    @Override
    public void handleRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.handleRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == 1001) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("CallKitVoip", "READ_PHONE_NUMBERS permission granted by user");
                Context context = getContext();
                if (context != null) {
                    boolean isEnabled = isPhoneAccountEnabled(context);
                    Log.d("CallKitVoip", "PhoneAccount enabled status after permission grant: " + isEnabled);
                }
            } else {
                Log.w("CallKitVoip", "READ_PHONE_NUMBERS permission denied by user");
            }
        }
    }

    public void notifyEvent(String eventName, String connectionId) {
        CallConfig config = connectionIdRegistry.get(connectionId);
        if (config == null) {
            Log.e("CallKitVoip", "No call config found for connectionId: " + connectionId);
            return;
        }

        Log.d("notifyEvent", eventName + "  " + config.username + "   " + connectionId);

        JSObject data = new JSObject();
        data.put("callId", config.callId);
        data.put("media", config.media);
        data.put("duration", config.duration);
        data.put("bookingId", config.bookingId);
        data.put("host", config.host);
        data.put("username", config.username);
        data.put("secret", config.secret);
        notifyListeners(eventName, data);
    }

    public void notifyRegistration(String token) {
        JSObject data = new JSObject();
        data.put("value", token);
        notifyListeners("registration", data);
        Log.d("CallKitVoip", "Registration event fired with token " + token);
    }

    public static void storeCallConfig(String connectionId, CallConfig config) {
        connectionIdRegistry.put(connectionId, config);
    }

    public static CallConfig getCallConfig(String connectionId) {
        return connectionIdRegistry.get(connectionId);
    }

    public static void removeCallConfig(String connectionId) {
        connectionIdRegistry.remove(connectionId);
    }

    public static Map<String, CallConfig> getAllCallConfigs() {
        return new HashMap<>(connectionIdRegistry);
    }

    @PluginMethod
    public void answerCall(PluginCall call) {
        String connectionId = call.getString("connectionId");
        if (connectionId != null) {
            CallQualityMonitor.trackCallEnd(connectionId, "User answered");
            notifyEvent("callAnswered", connectionId);
        }
        call.resolve();
    }

    @PluginMethod
    public void rejectCall(PluginCall call) {
        String connectionId = call.getString("connectionId");
        if (connectionId != null) {
            CallQualityMonitor.trackCallEnd(connectionId, "User rejected");
            notifyEvent("callRejected", connectionId);
            removeCallConfig(connectionId);
            CallStateManager.clearCallState(getContext(), connectionId);
            CallQualityMonitor.clearMetrics(connectionId);
        }
        call.resolve();
    }

    @PluginMethod
    public void hangupCall(PluginCall call) {
        String connectionId = call.getString("connectionId");
        if (connectionId != null) {
            CallQualityMonitor.trackCallEnd(connectionId, "User hangup");
            notifyEvent("callEnded", connectionId);
            removeCallConfig(connectionId);
            CallStateManager.clearCallState(getContext(), connectionId);
            CallQualityMonitor.clearMetrics(connectionId);
        }
        call.resolve();
    }
    
    @PluginMethod
    public void getCallMetrics(PluginCall call) {
        String connectionId = call.getString("connectionId");
        if (connectionId == null) {
            call.reject("connectionId is required");
            return;
        }
        
        Map<String, Object> metrics = CallQualityMonitor.getCallMetrics(connectionId);
        JSObject ret = new JSObject();
        for (Map.Entry<String, Object> entry : metrics.entrySet()) {
            ret.put(entry.getKey(), entry.getValue());
        }
        call.resolve(ret);
    }

    @PluginMethod
    public void requestPhoneNumbersPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Context context = getContext();
            if (context == null) {
                call.reject("Context is null, cannot request permission");
                return;
            }
            
            String[] permissions;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissions = new String[]{
                    android.Manifest.permission.READ_PHONE_STATE,
                    android.Manifest.permission.READ_PHONE_NUMBERS
                };
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                permissions = new String[]{
                    android.Manifest.permission.READ_PHONE_STATE,
                    android.Manifest.permission.READ_PHONE_NUMBERS
                };
            } else {
                permissions = new String[]{
                    android.Manifest.permission.READ_PHONE_STATE
                };
            }
            
            boolean allGranted = true;
            for (String permission : permissions) {
                if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            
            if (allGranted) {
                JSObject ret = new JSObject();
                ret.put("granted", true);
                ret.put("message", "Phone permissions already granted");
                call.resolve(ret);
                return;
            }
            
            Log.d("CallKitVoip", "Requesting phone permissions...");
            
            bridge.getActivity().runOnUiThread(() -> {
                ActivityCompat.requestPermissions(getActivity(), permissions, 1001);
            });
            
            JSObject ret = new JSObject();
            ret.put("granted", false);
            ret.put("message", "Permission request sent. Check handleRequestPermissionsResult for result.");
            call.resolve(ret);
        } else {
            JSObject ret = new JSObject();
            ret.put("granted", true);
            ret.put("message", "Phone permissions not required on Android versions below Marshmallow");
            call.resolve(ret);
        }
    }

    @PluginMethod
    public void checkPhoneAccountStatus(PluginCall call) {
        JSObject ret = new JSObject();
        
        boolean isSupported = PhoneAccountHelper.isPhoneAccountSupported();
        ret.put("supported", isSupported);
        
        if (isSupported) {
            boolean isEnabled = PhoneAccountHelper.isPhoneAccountEnabled(getContext());
            ret.put("enabled", isEnabled);
            
            if (!isEnabled) {
                ret.put("message", "Phone account needs to be enabled for native call UI");
                ret.put("instructions", "Go to Settings → Calls → Calling accounts → VoIP Account → Toggle ON");
                ret.put("canOpenSettings", true);
            } else {
                ret.put("message", "Phone account is enabled");
            }
        } else {
            ret.put("enabled", false);
            ret.put("message", "PhoneAccount not supported on Android versions below 6.0");
            ret.put("canOpenSettings", false);
        }
        
        Log.d("CallKitVoip", "PhoneAccount status check: " + ret.toString());
        call.resolve(ret);
    }

    @PluginMethod
    public void openPhoneAccountSettings(PluginCall call) {
        if (!PhoneAccountHelper.isPhoneAccountSupported()) {
            call.reject("PhoneAccount settings not available on this Android version");
            return;
        }
        
        try {
            PhoneAccountHelper.openPhoneAccountSettings(getContext());
            call.resolve();
        } catch (Exception e) {
            Log.e("CallKitVoip", "Error opening phone account settings", e);
            call.reject("Cannot open settings: " + e.getMessage());
        }
    }

    @PluginMethod
    public void requestNotificationPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(getContext(), 
                    android.Manifest.permission.POST_NOTIFICATIONS) 
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(getActivity(), 
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1002);
            }
        }
        call.resolve();
    }

    public void notifyError(String errorCode, String errorMessage) {
        JSObject data = new JSObject();
        data.put("code", errorCode);
        data.put("message", errorMessage);
        notifyListeners("error", data);
        Log.e("CallKitVoip", "Error: " + errorCode + " - " + errorMessage);
    }

    public static CallKitVoipPlugin getInstance() {
        if (staticBridge == null || staticBridge.getWebView() == null)
            return null;

        PluginHandle handler = staticBridge.getPlugin("CallKitVoip");
        return handler == null ? null : (CallKitVoipPlugin) handler.getInstance();
    }
}
