# Android Plugin Review: CallKit VoIP Plugin

**Status:** ⚠️ OUTDATED - See ANDROID_IMPLEMENTATION_REVIEW.md for current review  
**Last Updated:** January 7, 2026

## ⚠️ Important Notice

This review is outdated. The plugin has been significantly updated since this document was created.

**For current information, please refer to:**
- **ANDROID_IMPLEMENTATION_REVIEW.md** - Comprehensive current implementation review
- **ANDROID_REFACTOR_PLAN.md** - Proposed refactoring strategy

## Quick Summary of Current State

The plugin now uses:
- ✅ Android ConnectionService with self-managed PhoneAccount (not Twilio)
- ✅ Firebase Cloud Messaging for push notifications
- ✅ Native Android call UI through TelecomManager
- ⚠️ VoipForegroundService (implemented but not currently used)
- ⚠️ VoipBackgroundService (empty implementation)

## Critical Issue Identified

**Incoming call UI is not displaying because:**
1. PhoneAccount is disabled by default and must be manually enabled by user
2. No fallback mechanism when PhoneAccount is unavailable
3. Missing permissions for Android 13+ (POST_NOTIFICATIONS)

See detailed analysis in **ANDROID_IMPLEMENTATION_REVIEW.md**

---

## Legacy Documentation (Archived)

The following documentation reflects an older version of the plugin:

## Overview
This Capacitor plugin provides VoIP call functionality for Android using Firebase Cloud Messaging (FCM) for incoming call notifications and native Android ConnectionService for call handling.

## Architecture

### Key Components

1. **CallKitVoipPlugin.java** - Main plugin entry point
   - Handles registration with FCM topics
   - Manages PhoneAccount registration
   - Manages plugin lifecycle

2. **MyFirebaseMessagingService.java** - FCM message receiver
   - Receives incoming call notifications via FCM
   - Triggers native call UI through TelecomManager

3. **VoipBackgroundService.java** - Background service
   - ⚠️ Currently empty / unused

4. **VoipForegroundService.java** - Foreground service
   - ⚠️ Implemented but not called
   - Could serve as fallback UI

5. **MyConnectionService.java** - Android Telecom Connection Service
   - Integrates with Android's native call UI
   - Handles call answer/reject/disconnect

## Prerequisites

### 1. Firebase Setup

#### Required Files:
- `google-services.json` - Must be placed in `android/app/` directory
- Firebase project with Cloud Messaging enabled

#### Firebase Console Configuration:
1. Create a Firebase project at https://console.firebase.google.com
2. Add Android app to project
3. Download `google-services.json`
4. Enable Cloud Messaging API

### 2. Build Configuration

#### Project-level `build.gradle` (android/build.gradle):
```gradle
buildscript {
    dependencies {
        classpath 'com.google.gms:google-services:4.4.0'
    }
}
```

#### App-level `build.gradle` (android/app/build.gradle):
```gradle
apply plugin: 'com.google.gms.google-services'

dependencies {
    implementation 'com.google.firebase:firebase-messaging:23.0.0'
}
```

**Note**: The plugin's `build.gradle` already includes Firebase dependencies, but the main app must also apply the google-services plugin.

### 3. Permissions

The plugin requires these permissions (already declared in AndroidManifest.xml):
- `CALL_PHONE`
- `DISABLE_KEYGUARD`
- `MANAGE_OWN_CALLS`
- `INTERNET`
- `VIBRATE`

### 4. Runtime Permissions

For Android 6.0+, you may need to request runtime permissions:
- Phone permission (for making calls)
- Notification permission (Android 13+)

### 5. Dependencies

The plugin requires:
- Capacitor Android
- Firebase Messaging SDK
- Twilio Video Android SDK (v5.8.0)
- AndroidX AppCompat

## How to Use the Plugin

### 1. Installation

```bash
npm install capacitor-plugin-callkit-voip
npx cap sync
```

### 2. TypeScript/JavaScript Usage

```typescript
import { CallKitVoip } from 'capacitor-plugin-callkit-voip';

// Register the plugin with a user token (used as FCM topic)
await CallKitVoip.register({ userToken: 'user-123' });

// Listen for registration events
CallKitVoip.addListener('registration', (token: CallToken) => {
  console.log('VOIP token:', token.value);
});

// Listen for call events
CallKitVoip.addListener('callAnswered', (data: CallData) => {
  console.log('Call answered:', data);
});
```

### 3. FCM Topic Subscription

The `register()` method subscribes the device to an FCM topic using the `userToken` parameter:
- Topic name = `userToken` value
- Device receives all messages sent to this topic

## FCM Integration Details

### How FCM Works in This Plugin

1. **Registration Flow**:
   ```
   App calls register(userToken) 
   → Plugin subscribes to FCM topic (userToken)
   → Device receives FCM token (handled by Firebase SDK)
   ```

2. **Incoming Call Flow**:
   ```
   Server sends FCM message to topic
   → MyFirebaseMessagingService.onMessageReceived()
   → Checks for type="call" in data payload
   → Starts VoipBackgroundService
   → Service fetches Twilio token
   → Launches VoipForegroundService
   → Shows call UI
   ```

### FCM Message Format

The plugin expects FCM messages with this data structure:

```json
{
  "data": {
    "type": "call",
    "connectionId": "unique-call-id",
    "username": "caller-name"
  }
}
```

**Important**: Use `data` payload, not `notification` payload, to ensure `onMessageReceived()` is called even when app is in foreground.

### Sending FCM Messages

#### Using Firebase Admin SDK (Node.js):
```javascript
const admin = require('firebase-admin');

const message = {
  data: {
    type: 'call',
    connectionId: 'call-123',
    username: 'John Doe'
  },
  topic: 'user-123' // The userToken used in register()
};

admin.messaging().send(message)
  .then(response => console.log('Success:', response))
  .catch(error => console.error('Error:', error));
```

#### Using cURL:
```bash
curl -X POST https://fcm.googleapis.com/v1/projects/YOUR_PROJECT_ID/messages:send \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "message": {
      "topic": "user-123",
      "data": {
        "type": "call",
        "connectionId": "call-123",
        "username": "John Doe"
      }
    }
  }'
```

### FCM Token Management

**Current Issue**: The plugin doesn't implement `onNewToken()` callback, which means:
- Token refresh events are not handled
- App won't receive new tokens when FCM token is refreshed

**Recommended Fix**: Add token refresh handling in `MyFirebaseMessagingService`:

```java
@Override
public void onNewToken(String token) {
    Log.d(TAG, "Refreshed token: " + token);
    // Send token to your server or notify plugin
}
```

## Issues Found

### 1. Critical: Package Name Mismatch

**Location**: `AndroidManifest.xml`

**Problem**: Manifest uses `sa.ihorizon.public.callkitvoip` but Java classes are in `com.bfine.capactior.callkitvoip`

**Impact**: Services won't be found, causing runtime crashes

**Fix Required**: Update AndroidManifest.xml to use correct package names:
```xml
<service android:name="com.bfine.capactior.callkitvoip.MyFirebaseMessagingService">
<service android:name="com.bfine.capactior.callkitvoip.androidcall.VoipBackgroundService">
<service android:name="com.bfine.capactior.callkitvoip.MyConnectionService">
```

### 2. Syntax Error: Missing Closing Brace

**Location**: `MyFirebaseMessagingService.java` line 54

**Problem**: Missing closing brace for `onMessageReceived()` method

**Fix Required**: Add closing brace after line 54

### 3. Duplicate Dependency

**Location**: `build.gradle` lines 57 and 60

**Problem**: Firebase Messaging dependency declared twice with different versions

**Fix Required**: Remove duplicate, keep only one version (preferably newer)

### 4. Missing google-services.json Integration

**Problem**: Plugin doesn't include google-services plugin application

**Impact**: Firebase won't initialize properly

**Fix Required**: Main app must apply google-services plugin in its build.gradle

### 5. Hardcoded Package Name

**Location**: `MyConnectionService.java` line 56

**Problem**: Hardcoded package name `"app.bettercall"` for launch intent

**Fix Required**: Use dynamic package name from context

### 6. Missing Token Refresh Handler

**Problem**: `onNewToken()` not implemented

**Impact**: Token refresh events not handled

**Fix Required**: Implement token refresh callback

## Testing Checklist

- [ ] Firebase project created and configured
- [ ] `google-services.json` added to android/app/
- [ ] google-services plugin applied in app build.gradle
- [ ] Package names match between manifest and Java classes
- [ ] FCM topic subscription works
- [ ] Incoming FCM messages trigger call UI
- [ ] Call answer/reject works
- [ ] App handles FCM messages when in background
- [ ] App handles FCM messages when in foreground
- [ ] App handles FCM messages when killed

## Troubleshooting

### FCM Messages Not Received

1. Check `google-services.json` is in correct location
2. Verify google-services plugin is applied
3. Check Firebase Console for message delivery status
4. Verify topic subscription succeeded (check logs)
5. Ensure using `data` payload, not `notification` payload

### Call UI Not Showing

1. Check VoipBackgroundService logs
2. Verify Twilio token API call succeeds
3. Check VoipForegroundService is started
4. Verify permissions are granted

### Build Errors

1. Ensure all package names match
2. Check Firebase dependencies versions
3. Verify Capacitor version compatibility
4. Clean and rebuild project

## Additional Notes

- The plugin uses Twilio Video SDK for call handling
- Requires backend API to provide Twilio tokens
- ConnectionService integrates with Android's native call UI
- Foreground service ensures call UI stays active
- Background service handles FCM-triggered calls

