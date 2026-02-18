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
import java.util.concurrent.ConcurrentHashMap;

@CapacitorPlugin(name = "CallKitVoip")
public class CallKitVoipPlugin extends Plugin {
    public static Bridge staticBridge = null;
    private static Map<String, CallConfig> connectionIdRegistry = new HashMap<>();
    private static PhoneAccountHandle phoneAccountHandle = null;
    private static String cachedVoipToken = null;
    private static Map<String, Boolean> listenerRegistrationMap = new ConcurrentHashMap<>();
    private static boolean queueFlushScheduled = false;
    /** ConnectionId to notify callAnswered when RECORD_AUDIO permission result is received (late-invite: request mic at answer). */
    private static String pendingAnswerConnectionId = null;
    private static final int REQUEST_CODE_MICROPHONE_AT_ANSWER = 1003;

    @Override
    public void load() {
        staticBridge = this.bridge;
        Context context = this.getActivity().getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            registerPhoneAccount(context);
        }
        restoreCallStates(context);
        restoreAndFlushQueuedEvents(context);
        handleAppLaunchIntent();
        startPeriodicQueueFlushCheck();
    }
    
    private void restoreAndFlushQueuedEvents(Context context) {
        java.util.List<EventQueueManager.QueuedEvent> queuedEvents = EventQueueManager.getQueuedEvents(context);
        if (!queuedEvents.isEmpty()) {
            Log.d("CallKitVoip", "Found " + queuedEvents.size() + " queued events from previous session, will flush when listeners are registered");
        }
    }
    
    private void startPeriodicQueueFlushCheck() {
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (checkHasListeners("callAnswered") || checkHasListeners("callRejected")) {
                    flushQueuedEvents();
                } else {
                    handler.postDelayed(this, 500);
                }
            }
        }, 500);
    }
    
    private void handleAppLaunchIntent() {
        try {
            if (getActivity() == null || getActivity().getIntent() == null) {
                return;
            }
            
            android.content.Intent intent = getActivity().getIntent();
            boolean callAnswered = intent.getBooleanExtra("callAnswered", false);
            boolean isIncomingCall = intent.getBooleanExtra("isIncomingCall", false);
            String connectionId = intent.getStringExtra("connectionId");
            
            if (callAnswered && isIncomingCall && connectionId != null && !connectionId.isEmpty()) {
                Log.d("CallKitVoip", "App launched from answer button, connectionId: " + connectionId);
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    getActivity().setShowWhenLocked(true);
                    getActivity().setTurnScreenOn(true);
                } else {
                    getActivity().getWindow().addFlags(
                        android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                        android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    );
                }
                
                android.content.Intent serviceIntent = new android.content.Intent(getContext(), com.bfine.capactior.callkitvoip.androidcall.VoipForegroundService.class);
                serviceIntent.setAction("answered");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    getContext().startForegroundService(serviceIntent);
                } else {
                    getContext().startService(serviceIntent);
                }
                
                final String finalConnectionId = connectionId;
                if (ContextCompat.checkSelfPermission(getContext(), android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    bridge.getActivity().runOnUiThread(() -> {
                        try {
                            Thread.sleep(500);
                            notifyEvent("callAnswered", finalConnectionId);
                            Log.d("CallKitVoip", "callAnswered event fired for connectionId: " + finalConnectionId);
                        } catch (InterruptedException e) {
                            Log.e("CallKitVoip", "Error in delayed callback", e);
                        }
                    });
                } else {
                    pendingAnswerConnectionId = finalConnectionId;
                    ActivityCompat.requestPermissions(getActivity(), new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_CODE_MICROPHONE_AT_ANSWER);
                    Log.d("CallKitVoip", "Requesting RECORD_AUDIO at answer for connectionId: " + finalConnectionId);
                }
                
                intent.removeExtra("callAnswered");
                intent.removeExtra("isIncomingCall");
                intent.removeExtra("connectionId");
            }
        } catch (Exception e) {
            Log.e("CallKitVoip", "Error handling app launch intent", e);
        }
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
        Log.d("CallKitVoip", "📱 CallKitVoip: Starting registration...");

        if (topicName == null) {
            call.reject("Topic name hasn't been specified correctly");
            return;
        }
        
        requestPhoneNumbersPermissionIfNeeded();
        
        FirebaseMessaging
            .getInstance()
            .getToken()
            .addOnSuccessListener(token -> {
                Log.d("CallKitVoip", "📱 CallKitVoip: FCM token received: " + token);
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
                
                if (bridge != null && bridge.getActivity() != null) {
                    android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
                    handler.postDelayed(() -> {
                        if (cachedVoipToken != null) {
                            Log.d("CallKitVoip", "📱 CallKitVoip: Emitting cached token via delayed check (1s): " + cachedVoipToken);
                            notifyRegistration(cachedVoipToken);
                        } else {
                            Log.w("CallKitVoip", "⚠️ CallKitVoip: No token available after registration (token may not have been received)");
                        }
                    }, 1000);
                    
                    handler.postDelayed(() -> {
                        if (cachedVoipToken != null) {
                            Log.d("CallKitVoip", "📱 CallKitVoip: Emitting cached token via delayed check (3s): " + cachedVoipToken);
                            notifyRegistration(cachedVoipToken);
                        }
                    }, 3000);
                }
            })
            .addOnFailureListener(e -> {
                Logger.debug("CallKit: Cannot get token");
                call.reject("Cannot get FCM token: " + e.getMessage());
            });
    }

    @PluginMethod
    public void getVoipToken(PluginCall call) {
        if (cachedVoipToken != null) {
            JSObject ret = new JSObject();
            ret.put("value", cachedVoipToken);
            call.resolve(ret);
            Log.d("CallKitVoip", "Retrieved cached VoIP token: " + cachedVoipToken);
        } else {
            call.reject("Token not available yet");
            Log.w("CallKitVoip", "Attempted to get VoIP token but none available");
        }
    }

    @PluginMethod
    public void emitRegistrationEvent(PluginCall call) {
        if (cachedVoipToken != null) {
            Log.d("CallKitVoip", "📱 CallKitVoip: Manually emitting registration event for cached token");
            notifyRegistration(cachedVoipToken);
            call.resolve();
        } else {
            call.reject("Token not available yet");
            Log.w("CallKitVoip", "Attempted to emit registration event but no token available");
        }
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
    protected void handleOnNewIntent(android.content.Intent intent) {
        super.handleOnNewIntent(intent);
        
        try {
            boolean callAnswered = intent.getBooleanExtra("callAnswered", false);
            boolean isIncomingCall = intent.getBooleanExtra("isIncomingCall", false);
            String connectionId = intent.getStringExtra("connectionId");
            
            if (callAnswered && isIncomingCall && connectionId != null && !connectionId.isEmpty()) {
                Log.d("CallKitVoip", "App received new intent from answer button, connectionId: " + connectionId + " (request mic at answer)");
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    getActivity().setShowWhenLocked(true);
                    getActivity().setTurnScreenOn(true);
                } else {
                    getActivity().getWindow().addFlags(
                        android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                        android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    );
                }
                
                android.content.Intent serviceIntent = new android.content.Intent(getContext(), com.bfine.capactior.callkitvoip.androidcall.VoipForegroundService.class);
                serviceIntent.setAction("answered");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    getContext().startForegroundService(serviceIntent);
                } else {
                    getContext().startService(serviceIntent);
                }
                
                if (ContextCompat.checkSelfPermission(getContext(), android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    notifyEvent("callAnswered", connectionId);
                    Log.d("CallKitVoip", "callAnswered event fired for connectionId: " + connectionId);
                } else {
                    pendingAnswerConnectionId = connectionId;
                    ActivityCompat.requestPermissions(getActivity(), new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_CODE_MICROPHONE_AT_ANSWER);
                    Log.d("CallKitVoip", "Requesting RECORD_AUDIO at answer for connectionId: " + connectionId);
                }
                
                intent.removeExtra("callAnswered");
                intent.removeExtra("isIncomingCall");
                intent.removeExtra("connectionId");
            }
        } catch (Exception e) {
            Log.e("CallKitVoip", "Error handling new intent", e);
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
        } else if (requestCode == REQUEST_CODE_MICROPHONE_AT_ANSWER && pendingAnswerConnectionId != null) {
            String connectionId = pendingAnswerConnectionId;
            pendingAnswerConnectionId = null;
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Log.d("CallKitVoip", "Microphone at answer: " + (granted ? "granted" : "denied") + ", notifying callAnswered for " + connectionId);
            notifyEvent("callAnswered", connectionId);
        }
    }
    
    /**
     * Request microphone permission at answer time (late-invite approach). If already granted, notifies
     * callAnswered immediately; otherwise requests permission and notifies when user responds in handleRequestPermissionsResult.
     */
    public void requestMicrophoneThenNotifyCallAnswered(String connectionId) {
        if (getActivity() == null) {
            Log.w("CallKitVoip", "No activity for microphone request, notifying callAnswered anyway");
            notifyEvent("callAnswered", connectionId);
            return;
        }
        if (ContextCompat.checkSelfPermission(getContext(), android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            notifyEvent("callAnswered", connectionId);
            return;
        }
        pendingAnswerConnectionId = connectionId;
        ActivityCompat.requestPermissions(getActivity(), new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_CODE_MICROPHONE_AT_ANSWER);
        Log.d("CallKitVoip", "Requesting RECORD_AUDIO at answer for connectionId: " + connectionId);
    }

    public void notifyEvent(String eventName, String connectionId) {
        CallConfig config = connectionIdRegistry.get(connectionId);
        if (config == null) {
            Log.e("CallKitVoip", "No call config found for connectionId: " + connectionId);
            return;
        }

        Log.d("notifyEvent", eventName + "  " + config.getDisplayName() + "   " + connectionId);

        if (eventName.equals("callAnswered") || eventName.equals("callRejected")) {
            boolean hasListeners = checkHasListeners(eventName);
            
            if (!hasListeners) {
                Log.d("CallKitVoip", "No listeners registered for " + eventName + ", queuing event for connectionId: " + connectionId);
                EventQueueManager.queueEvent(getContext(), eventName, connectionId);
                return;
            } else {
                scheduleQueueFlush();
            }
        }

        JSObject data = new JSObject();
        data.put("callId", config.callId);
        data.put("media", config.media);
        data.put("duration", config.duration);
        data.put("bookingId", config.bookingId);
        notifyListeners(eventName, data);
    }
    
    private boolean checkHasListeners(String eventName) {
        try {
            java.lang.reflect.Method method = Plugin.class.getDeclaredMethod("hasListeners", String.class);
            method.setAccessible(true);
            boolean hasListeners = (Boolean) method.invoke(this, eventName);
            if (hasListeners && (eventName.equals("callAnswered") || eventName.equals("callRejected"))) {
                listenerRegistrationMap.put(eventName, true);
            }
            return hasListeners;
        } catch (Exception e) {
            Boolean registered = listenerRegistrationMap.get(eventName);
            if (registered != null && registered) {
                return true;
            }
            
            try {
                java.lang.reflect.Field listenersField = Plugin.class.getDeclaredField("listeners");
                listenersField.setAccessible(true);
                Object listenersObj = listenersField.get(this);
                if (listenersObj instanceof Map) {
                    Map<?, ?> listeners = (Map<?, ?>) listenersObj;
                    Object listenerList = listeners.get(eventName);
                    if (listenerList instanceof java.util.List) {
                        java.util.List<?> list = (java.util.List<?>) listenerList;
                        boolean hasListeners = !list.isEmpty();
                        if (hasListeners && (eventName.equals("callAnswered") || eventName.equals("callRejected"))) {
                            listenerRegistrationMap.put(eventName, true);
                            scheduleQueueFlush();
                        }
                        return hasListeners;
                    }
                }
            } catch (Exception ex) {
            }
            
            return false;
        }
    }
    
    private void scheduleQueueFlush() {
        if (!queueFlushScheduled) {
            queueFlushScheduled = true;
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            handler.postDelayed(() -> {
                flushQueuedEvents();
                queueFlushScheduled = false;
            }, 100);
        }
    }
    
    private void flushQueuedEvents() {
        Context context = getContext();
        if (context == null) {
            Log.w("CallKitVoip", "Context is null, cannot flush queued events");
            return;
        }
        
        java.util.List<EventQueueManager.QueuedEvent> queuedEvents = EventQueueManager.getQueuedEvents(context);
        if (queuedEvents.isEmpty()) {
            Log.d("CallKitVoip", "No queued events to flush");
            return;
        }
        
        Log.d("CallKitVoip", "Flushing " + queuedEvents.size() + " queued events");
        
        java.util.List<EventQueueManager.QueuedEvent> eventsToRemove = new java.util.ArrayList<>();
        
        for (EventQueueManager.QueuedEvent event : queuedEvents) {
            CallConfig config = connectionIdRegistry.get(event.connectionId);
            if (config == null) {
                Log.w("CallKitVoip", "Call config not found for queued event connectionId: " + event.connectionId + ", removing from queue");
                EventQueueManager.removeEvent(context, event.eventName, event.connectionId);
                continue;
            }
            
            boolean hasListeners = checkHasListeners(event.eventName);
            if (hasListeners) {
                JSObject data = new JSObject();
                data.put("callId", config.callId);
                data.put("media", config.media);
                data.put("duration", config.duration);
                data.put("bookingId", config.bookingId);
                
                notifyListeners(event.eventName, data);
                Log.d("CallKitVoip", "Flushed queued event: " + event.eventName + " for connectionId: " + event.connectionId);
                
                eventsToRemove.add(event);
            } else {
                Log.w("CallKitVoip", "No listeners for " + event.eventName + " yet, keeping event in queue");
            }
        }
        
        for (EventQueueManager.QueuedEvent event : eventsToRemove) {
            EventQueueManager.removeEvent(context, event.eventName, event.connectionId);
        }
        
        Log.d("CallKitVoip", "Finished flushing queued events, removed " + eventsToRemove.size() + " events");
    }

    public void notifyRegistration(String token) {
        Log.d("CallKitVoip", "📱 CallKitVoip: notifyRegistration called with token: " + token);
        cachedVoipToken = token;
        JSObject data = new JSObject();
        data.put("value", token);
        
        if (bridge != null && bridge.getActivity() != null) {
            bridge.getActivity().runOnUiThread(() -> {
                Log.d("CallKitVoip", "📱 CallKitVoip: Emitting registration event for token: " + token);
                notifyListeners("registration", data);
                Log.d("CallKitVoip", "📱 CallKitVoip: Registration event emitted successfully");
            });
        } else {
            Log.w("CallKitVoip", "⚠️ CallKitVoip: Bridge or activity is null, cannot emit registration event");
            notifyListeners("registration", data);
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            MyConnectionService.destroyCurrentConnectionIfAny();
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
        
        if (Build.VERSION.SDK_INT >= 34) {
            android.app.NotificationManager notificationManager = 
                (android.app.NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null && !notificationManager.canUseFullScreenIntent()) {
                try {
                    android.content.Intent intent = new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT
                    );
                    intent.setData(android.net.Uri.parse("package:" + getContext().getPackageName()));
                    getActivity().startActivity(intent);
                    Log.d("CallKitVoip", "Redirecting user to enable Full Screen Intent permission");
                } catch (Exception e) {
                    Log.e("CallKitVoip", "Error opening full screen intent settings", e);
                }
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
