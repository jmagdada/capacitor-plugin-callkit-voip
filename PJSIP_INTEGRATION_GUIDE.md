# PJSIP Integration Guide

## Overview
This guide shows how to modify this plugin to work with your existing PJSIP plugin. The goal is to:
1. ✅ Keep FCM push notifications
2. ✅ Show native Android ConnectionService call UI
3. ✅ Remove Twilio-specific code
4. ✅ Integrate with your PJSIP plugin

## What to Keep

### 1. FCM Push Notification System
- ✅ `MyFirebaseMessagingService.java` - Receives FCM messages
- ✅ `CallKitVoipPlugin.java` - Registers FCM topics
- ✅ Firebase dependencies in `build.gradle`

### 2. Native Call UI (ConnectionService)
- ✅ `MyConnectionService.java` - Shows native Android call UI
- ✅ ConnectionService declaration in `AndroidManifest.xml`

### 3. Notification Service (Optional)
- ✅ `VoipForegroundService.java` - Shows call notification (can keep or remove)
- ✅ `VoipForegroundServiceActionReceiver.java` - Handles notification actions

## What to Remove/Modify

### 1. Remove Twilio Dependencies
**In `android/build.gradle`:**
```gradle
// REMOVE this line:
implementation 'com.twilio:video-android:5.8.0'
```

### 2. Remove Twilio-Specific Files
- ❌ `CallActivity.java` - Not needed (your PJSIP plugin handles calls)
- ❌ `ApiCalls.java` - Remove Twilio token fetching
- ❌ `SettingsActivity.java` - Twilio codec settings
- ❌ `util/CameraCapturerCompat.java` - Twilio camera handling

### 3. Simplify VoipBackgroundService
Remove Twilio token fetching, just trigger ConnectionService.

## Modified Implementation

### Step 1: Update MyFirebaseMessagingService.java

**Current:** Fetches Twilio token, starts VoipBackgroundService

**Modified:** Directly shows ConnectionService call UI

```java
package com.bfine.capactior.callkitvoip;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.telecom.ConnectionRequest;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import androidx.annotation.RequiresApi;

@SuppressLint("MissingFirebaseInstanceTokenRefresh")
public class MyFirebaseMessagingService extends FirebaseMessagingService {

    private static final String TAG = "MyFirebaseMsgService";

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        Log.d(TAG, "received " + remoteMessage.getData());

        if (remoteMessage.getData().containsKey("type") && 
            remoteMessage.getData().get("type").equals("call")) {
            
            String connectionId = remoteMessage.getData().get("connectionId");
            String username = remoteMessage.getData().get("username");
            String fromNumber = remoteMessage.getData().get("from"); // SIP URI or phone number
            
            showIncomingCall(connectionId, username, fromNumber);
        }

        if (remoteMessage.getData().containsKey("type") && 
            remoteMessage.getData().get("type").equals("stopCall")) {
            // Handle call end notification
            endCall();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void showIncomingCall(String connectionId, String username, String fromNumber) {
        try {
            TelecomManager telecomManager = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
            
            if (telecomManager == null) {
                Log.e(TAG, "TelecomManager is null");
                return;
            }

            PhoneAccountHandle phoneAccountHandle = new PhoneAccountHandle(
                new ComponentName(this, MyConnectionService.class),
                "voip_account"
            );

            Bundle extras = new Bundle();
            extras.putString("connectionId", connectionId);
            extras.putString("username", username);
            extras.putString("from", fromNumber != null ? fromNumber : username);

            ConnectionRequest request = new ConnectionRequest.Builder()
                .setAddress(Uri.parse("tel:" + (fromNumber != null ? fromNumber : username)))
                .setExtras(extras)
                .build();

            telecomManager.addNewIncomingCall(phoneAccountHandle, request);
            
            Log.d(TAG, "Incoming call shown: " + username);
            
        } catch (Exception e) {
            Log.e(TAG, "Error showing incoming call", e);
        }
    }

    private void endCall() {
        // Notify your PJSIP plugin to end the call
        // You can use Capacitor plugin communication here
    }

    @Override
    public void onNewToken(String token) {
        Log.d(TAG, "Refreshed token: " + token);
        // Send token to your server or notify plugin
        CallKitVoipPlugin plugin = CallKitVoipPlugin.getInstance();
        if (plugin != null) {
            // Notify plugin about new token
        }
    }
}
```

### Step 2: Update MyConnectionService.java

**Modify to integrate with your PJSIP plugin:**

```java
package com.bfine.capactior.callkitvoip;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.getcapacitor.Bridge;

@RequiresApi(api = Build.VERSION_CODES.M)
public class MyConnectionService extends ConnectionService {

    private static final String TAG = "MyConnectionService";
    private static Connection currentConnection;

    public static Connection getConnection() {
        return currentConnection;
    }

    public static void deinitConnection() {
        currentConnection = null;
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public Connection onCreateIncomingConnection(
            final PhoneAccountHandle connectionManagerPhoneAccount, 
            final ConnectionRequest request) {
        
        final Connection connection = new Connection() {
            @RequiresApi(api = Build.VERSION_CODES.O)
            @Override
            public void onAnswer() {
                this.setActive();
                
                String connectionId = request.getExtras().getString("connectionId");
                String username = request.getExtras().getString("username");
                String fromNumber = request.getExtras().getString("from");
                
                Log.d(TAG, "Call answered - connectionId: " + connectionId + ", username: " + username);
                
                // Notify your PJSIP plugin to answer the call
                CallKitVoipPlugin plugin = CallKitVoipPlugin.getInstance();
                if (plugin != null) {
                    plugin.notifyEvent("callAnswered", username, connectionId);
                    
                    // Call your PJSIP plugin method to answer
                    // Example: plugin.answerPjsipCall(connectionId, fromNumber);
                }
            }

            @Override
            public void onReject() {
                String connectionId = request.getExtras().getString("connectionId");
                String username = request.getExtras().getString("username");
                
                Log.d(TAG, "Call rejected - connectionId: " + connectionId);
                
                // Notify your PJSIP plugin to reject the call
                CallKitVoipPlugin plugin = CallKitVoipPlugin.getInstance();
                if (plugin != null) {
                    plugin.notifyEvent("callRejected", username, connectionId);
                    
                    // Call your PJSIP plugin method to reject
                    // Example: plugin.rejectPjsipCall(connectionId);
                }
                
                DisconnectCause cause = new DisconnectCause(DisconnectCause.REJECTED);
                this.setDisconnected(cause);
                this.destroy();
                currentConnection = null;
            }

            @Override
            public void onAbort() {
                super.onAbort();
                currentConnection = null;
            }

            @Override
            public void onDisconnect() {
                String connectionId = request.getExtras().getString("connectionId");
                String username = request.getExtras().getString("username");
                
                Log.d(TAG, "Call disconnected - connectionId: " + connectionId);
                
                // Notify your PJSIP plugin to hangup
                CallKitVoipPlugin plugin = CallKitVoipPlugin.getInstance();
                if (plugin != null) {
                    plugin.notifyEvent("callEnded", username, connectionId);
                    
                    // Call your PJSIP plugin method to hangup
                    // Example: plugin.hangupPjsipCall(connectionId);
                }
                
                DisconnectCause cause = new DisconnectCause(DisconnectCause.LOCAL);
                this.setDisconnected(cause);
                this.destroy();
                currentConnection = null;
            }
        };
        
        String fromNumber = request.getExtras().getString("from");
        Uri addressUri = Uri.parse(fromNumber != null ? fromNumber : 
            request.getExtras().getString("username"));
        
        connection.setAddress(addressUri, TelecomManager.PRESENTATION_ALLOWED);
        connection.setCallerDisplayName(
            request.getExtras().getString("username"), 
            TelecomManager.PRESENTATION_ALLOWED
        );
        
        currentConnection = connection;
        return connection;
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public Connection onCreateOutgoingConnection(
            PhoneAccountHandle connectionManagerPhoneAccount, 
            ConnectionRequest request) {
        // Handle outgoing calls if needed
        // This is typically handled by your PJSIP plugin
        return super.onCreateOutgoingConnection(connectionManagerPhoneAccount, request);
    }
}
```

### Step 3: Update CallKitVoipPlugin.java

**Add methods to communicate with your PJSIP plugin:**

```java
package com.bfine.capactior.callkitvoip;

import com.getcapacitor.Bridge;
import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginHandle;
import com.getcapacitor.PluginMethod;
import com.google.firebase.messaging.FirebaseMessaging;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

public class CallKitVoipPlugin extends Plugin {
    public static Bridge staticBridge = null;

    @Override
    public void load() {
        staticBridge = this.bridge;
        this.getActivity().getApplicationContext();
    }

    @PluginMethod
    public void register(PluginCall call) {
        final String topicName = call.getString("userToken");
        Log.d("CallKitVoip", "register");

        if (topicName == null) {
            call.reject("Topic name hasn't been specified correctly");
            return;
        }
        
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
    }

    public void notifyEvent(String eventName, String username, String connectionId) {
        Log.d("notifyEvent", eventName + "  " + username + "   " + connectionId);

        JSObject data = new JSObject();
        data.put("username", username);
        data.put("connectionId", connectionId);
        notifyListeners(eventName, data);
    }

    // Add method to call your PJSIP plugin
    @PluginMethod
    public void answerCall(PluginCall call) {
        String connectionId = call.getString("connectionId");
        // Call your PJSIP plugin's answer method
        // Example: get your PJSIP plugin instance and call answer
        call.resolve();
    }

    @PluginMethod
    public void rejectCall(PluginCall call) {
        String connectionId = call.getString("connectionId");
        // Call your PJSIP plugin's reject method
        call.resolve();
    }

    @PluginMethod
    public void hangupCall(PluginCall call) {
        String connectionId = call.getString("connectionId");
        // Call your PJSIP plugin's hangup method
        call.resolve();
    }

    public static CallKitVoipPlugin getInstance() {
        if (staticBridge == null || staticBridge.getWebView() == null)
            return null;

        PluginHandle handler = staticBridge.getPlugin("CallKitVoip");
        return handler == null ? null : (CallKitVoipPlugin) handler.getInstance();
    }
}
```

### Step 4: Simplify VoipBackgroundService.java (Optional)

**If you want to keep notification service:**

```java
package com.bfine.capactior.callkitvoip.androidcall;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

public class VoipBackgroundService extends Service {
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d("VoipBackgroundService", "onStartCommand");
        
        // ConnectionService will handle the call UI
        // This service can be removed if not needed
        
        return START_NOT_STICKY;
    }
}
```

**Or remove it entirely** if ConnectionService handles everything.

### Step 5: Update AndroidManifest.xml

**Fix package names and remove Twilio-specific activities:**

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.CALL_PHONE"/>
    <uses-permission android:name="android.permission.DISABLE_KEYGUARD" />
    <uses-permission android:name="android.permission.MANAGE_OWN_CALLS"/>
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.VIBRATE" />

    <application android:usesCleartextTraffic="true">
        
        <!-- ConnectionService for native call UI -->
        <service 
            android:name="com.bfine.capactior.callkitvoip.MyConnectionService"
            android:permission="android.permission.BIND_TELECOM_CONNECTION_SERVICE"
            android:exported="false">
            <intent-filter>
                <action android:name="android.telecom.ConnectionService" />
            </intent-filter>
        </service>

        <!-- FCM Messaging Service -->
        <service
            android:name="com.bfine.capactior.callkitvoip.MyFirebaseMessagingService">
            <intent-filter>
                <action android:name="com.google.firebase.MESSAGING_EVENT" />
            </intent-filter>
        </service>

        <!-- Optional: Keep notification service if needed -->
        <service
            android:name="com.bfine.capactior.callkitvoip.androidcall.VoipForegroundService"
            android:enabled="true"
            android:stopWithTask="true"
            android:exported="true">
        </service>
        
        <receiver
            android:name="com.bfine.capactior.callkitvoip.androidcall.VoipForegroundServiceActionReceiver">
        </receiver>

        <!-- Remove CallActivity - not needed with PJSIP -->
        <!-- <activity android:name=".androidcall.CallActivity" ... /> -->
    </application>
</manifest>
```

### Step 6: Update build.gradle

**Remove Twilio dependency:**

```gradle
dependencies {
    implementation fileTree(dir: 'libs', include: ['*.jar'])
    implementation project(':capacitor-android')
    implementation "androidx.appcompat:appcompat:$androidxAppCompatVersion"
    implementation 'com.google.android.gms:play-services-tasks:17.2.1'
    implementation 'com.google.firebase:firebase-messaging:21.1.0'
    // Remove: implementation 'com.twilio:video-android:5.8.0'
    testImplementation "junit:junit:$junitVersion"
    androidTestImplementation "androidx.test.ext:junit:$androidxJunitVersion"
    androidTestImplementation "androidx.test.espresso:espresso-core:$androidxEspressoCoreVersion"
}
```

## Integration Flow

### Incoming Call Flow:
```
1. FCM message received
   → MyFirebaseMessagingService.onMessageReceived()
   
2. Extract call data (connectionId, username, from)
   
3. Show native call UI
   → TelecomManager.addNewIncomingCall()
   → MyConnectionService.onCreateIncomingConnection()
   
4. User answers/rejects
   → MyConnectionService.onAnswer() / onReject()
   
5. Notify your PJSIP plugin
   → CallKitVoipPlugin.notifyEvent()
   → Call your PJSIP plugin methods
```

## FCM Message Format

Your server should send FCM messages with this structure:

```json
{
  "data": {
    "type": "call",
    "connectionId": "unique-call-id",
    "username": "Caller Name",
    "from": "sip:caller@example.com"
  }
}
```

**Fields:**
- `type`: "call" for incoming call, "stopCall" to end call
- `connectionId`: Unique identifier for the call
- `username`: Display name for the caller
- `from`: SIP URI or phone number (optional, defaults to username)

## Connecting to Your PJSIP Plugin

### Option 1: Use Capacitor Plugin Communication

In your PJSIP plugin, listen for events:

```typescript
// In your app code
import { CallKitVoip } from 'capacitor-plugin-callkit-voip';
import { YourPjsipPlugin } from 'your-pjsip-plugin';

CallKitVoip.addListener('callAnswered', async (data) => {
  // Answer the call using your PJSIP plugin
  await YourPjsipPlugin.answerCall({ 
    callId: data.connectionId 
  });
});

CallKitVoip.addListener('callRejected', async (data) => {
  // Reject the call
  await YourPjsipPlugin.rejectCall({ 
    callId: data.connectionId 
  });
});
```

### Option 2: Direct Plugin-to-Plugin Communication

Modify `MyConnectionService.onAnswer()` to directly call your PJSIP plugin:

```java
@Override
public void onAnswer() {
    this.setActive();
    
    String connectionId = request.getExtras().getString("connectionId");
    
    // Get your PJSIP plugin instance
    PluginHandle pjsipHandler = staticBridge.getPlugin("YourPjsipPlugin");
    if (pjsipHandler != null) {
        YourPjsipPlugin pjsipPlugin = (YourPjsipPlugin) pjsipHandler.getInstance();
        pjsipPlugin.answerCall(connectionId);
    }
}
```

## Testing Checklist

- [ ] FCM messages are received
- [ ] Native call UI appears when FCM received
- [ ] Call UI shows correct caller name/number
- [ ] Answer button triggers PJSIP answer
- [ ] Reject button triggers PJSIP reject
- [ ] Hangup button triggers PJSIP hangup
- [ ] Call state updates correctly
- [ ] Works when app is in background
- [ ] Works when app is killed

## Summary

**Keep:**
- ✅ FCM push notification system
- ✅ ConnectionService for native call UI
- ✅ Firebase dependencies

**Remove:**
- ❌ Twilio Video SDK
- ❌ CallActivity.java
- ❌ ApiCalls.java (Twilio token fetching)
- ❌ Twilio-specific utilities

**Modify:**
- 🔧 MyFirebaseMessagingService - directly show ConnectionService
- 🔧 MyConnectionService - integrate with PJSIP plugin
- 🔧 CallKitVoipPlugin - add PJSIP communication methods

This approach gives you native Android call UI with FCM push notifications, while your PJSIP plugin handles the actual SIP call logic.

