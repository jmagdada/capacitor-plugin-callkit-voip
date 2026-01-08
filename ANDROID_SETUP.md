# Android Setup Guide - CallKit VoIP Plugin

This guide provides comprehensive instructions for setting up and using the CallKit VoIP plugin on Android.

## Table of Contents

- [Overview](#overview)
- [Prerequisites](#prerequisites)
- [Requirements](#requirements)
- [Installation](#installation)
- [Configuration](#configuration)
- [Usage](#usage)
- [FCM Integration](#fcm-integration)
- [Permissions](#permissions)
- [Troubleshooting](#troubleshooting)
- [API Reference](#api-reference)

## Overview

The CallKit VoIP plugin for Android enables VoIP (Voice over IP) call functionality using:

- **Firebase Cloud Messaging (FCM)** - For receiving incoming call notifications
- **Android ConnectionService** - For native call UI integration
- **Foreground Services** - For displaying call notifications and managing call state

The plugin integrates with Android's native call interface, allowing users to receive and manage VoIP calls through the system's built-in call UI.

## Prerequisites

Before setting up the plugin, ensure you have:

1. **Android Studio** - Latest stable version
2. **Capacitor Project** - A working Capacitor Android project
3. **Firebase Project** - A Firebase project with Cloud Messaging enabled
4. **Java Development Kit** - JDK 17 or higher
5. **Android SDK** - Minimum SDK 22, Target SDK 33 or higher

## Requirements

### Minimum Android Version

- **Minimum SDK**: 22 (Android 5.1 Lollipop)
- **Target SDK**: 33 (Android 13) or higher
- **ConnectionService Requirement**: Android 6.0+ (API 23) for full functionality

### Dependencies

The plugin requires the following dependencies (already included in the plugin):

- Capacitor Android SDK
- Firebase Messaging SDK (21.1.0)
- AndroidX AppCompat
- Google Play Services Tasks

### Firebase Requirements

- Firebase project with Cloud Messaging API enabled
- `google-services.json` configuration file
- Firebase Cloud Messaging service account or API key for sending messages

## Installation

### Step 1: Install the Plugin

```bash
npm install capacitor-plugin-callkit-voip
npx cap sync
```

### Step 2: Firebase Setup

#### 2.1 Create Firebase Project

1. Go to [Firebase Console](https://console.firebase.google.com)
2. Create a new project or select an existing one
3. Add an Android app to your project
4. Register your app with the package name from your `AndroidManifest.xml`

#### 2.2 Download google-services.json

1. Download the `google-services.json` file from Firebase Console
2. Place it in your app's root directory: `android/app/google-services.json`

**Important**: The file must be named exactly `google-services.json` and placed in the `android/app/` directory.

#### 2.3 Configure Build Files

**Project-level `build.gradle`** (`android/build.gradle`):

```gradle
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:8.0.0'
        classpath 'com.google.gms:google-services:4.4.0'
    }
}
```

**App-level `build.gradle`** (`android/app/build.gradle`):

Add at the top of the file (after other plugins):

```gradle
apply plugin: 'com.google.gms.google-services'
```

The plugin's build.gradle already includes Firebase dependencies, but your main app must apply the google-services plugin to initialize Firebase.

### Step 3: Verify Package Name

**Important**: Your app's package name must match the package name registered in Firebase Console.

1. **Find your app's package name**: Check your main app's `AndroidManifest.xml` (located at `android/app/src/main/AndroidManifest.xml`). Look for the `package` attribute in the `<manifest>` tag, or the `applicationId` in your `android/app/build.gradle` file.

   Example: If your app's package name is `com.digitaltolk.uk`, this is what you need to register in Firebase.

2. **Register in Firebase Console**: When adding your Android app to Firebase (Step 2.1), use your app's package name (e.g., `com.digitaltolk.uk`), NOT the plugin's package name.

3. **Verify in google-services.json**: After downloading `google-services.json`, open it and verify that the `package_name` field matches your app's package name.

**Note**: The plugin has its own internal package name (`com.bfine.capactior.callkitvoip`) which is used for the plugin's Java classes and services. This is separate from your app's package name and does NOT need to match Firebase. Your app's package name and the plugin's package name can be different - this is normal and expected.

## Configuration

### AndroidManifest.xml

The plugin automatically registers the following services in its manifest:

- `MyConnectionService` - Handles native call UI integration
- `MyFirebaseMessagingService` - Receives FCM messages
- `VoipForegroundService` - Displays call notifications
- `VoipBackgroundService` - Background service for call handling

No additional manifest configuration is required in your main app's `AndroidManifest.xml`.

### Permissions

The plugin declares the following permissions (automatically included):

```xml
<uses-permission android:name="android.permission.CALL_PHONE"/>
<uses-permission android:name="android.permission.DISABLE_KEYGUARD"/>
<uses-permission android:name="android.permission.MANAGE_OWN_CALLS"/>
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.VIBRATE"/>
```

#### Runtime Permissions (Android 6.0+)

For Android 6.0 and above, you may need to request runtime permissions:

```typescript
import { Permissions } from '@capacitor/core';

async function requestPermissions() {
  const result = await Permissions.request({
    name: 'phone'
  });
  
  if (result.state === 'granted') {
    console.log('Phone permission granted');
  }
}
```

#### Notification Permission (Android 13+)

For Android 13 (API 33) and above, notification permission is required:

```typescript
import { Permissions } from '@capacitor/core';

async function requestNotificationPermission() {
  if (Capacitor.getPlatform() === 'android') {
    const result = await Permissions.request({
      name: 'notifications'
    });
  }
}
```

## Usage

### Basic Setup

```typescript
import { CallKitVoip } from 'capacitor-plugin-callkit-voip';

async function initializeVoip() {
  try {
    await CallKitVoip.register({ userToken: 'user-123' });
    console.log('VoIP registered successfully');
  } catch (error) {
    console.error('Registration failed:', error);
  }
}
```

### Register for VoIP Calls

The `register()` method subscribes the device to an FCM topic using the provided `userToken`. This topic will be used to send incoming call notifications.

```typescript
await CallKitVoip.register({ userToken: 'unique-user-id' });
```

**Parameters:**
- `userToken` (string, required): A unique identifier for the user. This will be used as the FCM topic name.

**Returns:** Promise that resolves when subscription is successful.

### Listen for Events

#### Registration Event

Listen for when the device successfully registers for VoIP notifications:

```typescript
CallKitVoip.addListener('registration', (token: CallToken) => {
  console.log('VoIP token received:', token.value);
});
```

#### Call Answered Event

Listen for when a user answers an incoming call:

```typescript
CallKitVoip.addListener('callAnswered', (data: CallData) => {
  console.log('Call answered:', {
    username: data.username,
    connectionId: data.connectionId
  });
});
```

#### Call Rejected Event

Listen for when a user rejects an incoming call:

```typescript
CallKitVoip.addListener('callRejected', (data: CallData) => {
  console.log('Call rejected:', {
    username: data.username,
    connectionId: data.connectionId
  });
});
```

#### Call Ended Event

Listen for when a call ends:

```typescript
CallKitVoip.addListener('callEnded', (data: CallData) => {
  console.log('Call ended:', {
    username: data.username,
    connectionId: data.connectionId
  });
});
```

### Complete Example

```typescript
import { CallKitVoip, CallToken, CallData } from 'capacitor-plugin-callkit-voip';
import { App } from '@capacitor/app';

export class VoipService {
  async initialize(userId: string) {
    await CallKitVoip.register({ userToken: userId });
    
    CallKitVoip.addListener('registration', (token: CallToken) => {
      console.log('Registered with token:', token.value);
      this.sendTokenToServer(token.value);
    });
    
    CallKitVoip.addListener('callAnswered', (data: CallData) => {
      console.log('Call answered from:', data.username);
      console.log('Call details:', {
        callId: data.callId,
        media: data.media,
        bookingId: data.bookingId,
        host: data.host
      });
      this.handleIncomingCall(data);
    });
    
    CallKitVoip.addListener('callRejected', (data: CallData) => {
      console.log('Call rejected:', data.callId);
    });
    
    CallKitVoip.addListener('callEnded', (data: CallData) => {
      console.log('Call ended:', data.callId);
      this.handleCallEnded(data);
    });
  }
  
  private async sendTokenToServer(token: string) {
    await fetch('https://your-api.com/register-token', {
      method: 'POST',
      body: JSON.stringify({ token, platform: 'android' })
    });
  }
  
  private handleIncomingCall(data: CallData) {
    App.addListener('appStateChange', ({ isActive }) => {
      if (isActive) {
        this.navigateToCallScreen(data);
      }
    });
  }
  
  private handleCallEnded(data: CallData) {
    this.cleanupCall(data.callId);
  }
  
  private navigateToCallScreen(data: CallData) {
    // Navigate to your call screen
  }
  
  private cleanupCall(callId: string) {
    // Cleanup call resources
  }
}
```

## FCM Integration

### How It Works

1. **Registration**: App calls `register({ userToken: 'user-123' })`
2. **Topic Subscription**: Plugin subscribes to FCM topic named `user-123`
3. **Incoming Call**: Server sends FCM message to topic `user-123`
4. **Message Received**: `MyFirebaseMessagingService` receives the message
5. **Call UI**: Plugin shows native Android call UI via `ConnectionService`
6. **User Action**: User answers/rejects, plugin notifies your app via events

### FCM Message Format

The plugin expects FCM messages with the following data structure:

```json
{
  "data": {
    "type": "call",
    "connectionId": "unique-call-id-123",
    "callId": "call-id-123",
    "media": "audio",
    "duration": "0",
    "bookingId": "12345",
    "host": "sip.example.com",
    "username": "John Doe",
    "secret": "secret-key",
    "from": "+1234567890"
  }
}
```

**Important Fields:**
- `type`: Must be `"call"` for incoming calls or `"stopCall"` to end a call
- `connectionId`: Unique identifier for the call (required)
- `callId`: Call identifier (optional, defaults to connectionId if not provided)
- `media`: Media type - `"audio"` or `"video"` (optional, defaults to `"audio"`)
- `duration`: Call duration in seconds as string (optional, defaults to `"0"`)
- `bookingId`: Booking identifier as string (optional, defaults to `0`)
- `host`: SIP host or server address (optional, defaults to empty string)
- `username`: Display name for the caller (optional, defaults to `"Unknown"`)
- `secret`: Secret key for authentication (optional, defaults to empty string)
- `from`: Phone number or identifier (optional, defaults to username)

**Critical**: Use `data` payload, not `notification` payload. The `notification` payload will be handled by the system and won't trigger `onMessageReceived()` when the app is in the foreground.

### Sending FCM Messages

#### Using Firebase Admin SDK (Node.js)

```javascript
const admin = require('firebase-admin');

const serviceAccount = require('./path/to/serviceAccountKey.json');

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount)
});

async function sendIncomingCall(userToken, connectionId, callId, media, duration, bookingId, host, username, secret, fromNumber) {
  const message = {
    data: {
      type: 'call',
      connectionId: connectionId,
      callId: callId || connectionId,
      media: media || 'audio',
      duration: duration || '0',
      bookingId: bookingId ? String(bookingId) : '0',
      host: host || '',
      username: username || 'Unknown',
      secret: secret || '',
      from: fromNumber || username || 'Unknown'
    },
    topic: userToken
  };

  try {
    const response = await admin.messaging().send(message);
    console.log('Successfully sent message:', response);
    return response;
  } catch (error) {
    console.error('Error sending message:', error);
    throw error;
  }
}

async function endCall(userToken) {
  const message = {
    data: {
      type: 'stopCall'
    },
    topic: userToken
  };

  await admin.messaging().send(message);
}
```

#### Using cURL

**Getting YOUR_PROJECT_ID:**
1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Select your project
3. Click the gear icon (⚙️) next to "Project Overview" in the left sidebar
4. Click "Project settings"
5. Your **Project ID** is displayed at the top of the "General" tab (it's different from the Project name)

**Getting the Access Token:**
The `$(gcloud auth print-access-token)` is a command that generates an access token. You need to:
1. Install [Google Cloud SDK (gcloud CLI)](https://cloud.google.com/sdk/docs/install)
2. Authenticate with: `gcloud auth login`
3. Set your project: `gcloud config set project YOUR_PROJECT_ID`
4. The command `gcloud auth print-access-token` will then print a token that's valid for 1 hour

**Alternative:** If you don't want to use gcloud CLI, you can get an access token from:
- Firebase Console → Project Settings → Service Accounts → Generate new private key (creates a service account JSON)
- Use the service account JSON with Firebase Admin SDK (as shown in the Node.js example above)

```bash
curl -X POST https://fcm.googleapis.com/v1/projects/YOUR_PROJECT_ID/messages:send \
  -H "Authorization: Bearer $(gcloud auth print-access-token)" \
  -H "Content-Type: application/json" \
  -d '{
    "message": {
      "topic": "user-123",
      "data": {
        "type": "call",
        "connectionId": "call-123",
        "callId": "call-123",
        "media": "audio",
        "duration": "0",
        "bookingId": "12345",
        "host": "sip.example.com",
        "username": "John Doe",
        "secret": "secret-key",
        "from": "+1234567890"
      }
    }
  }'
```

#### Using HTTP v1 API

```javascript
const https = require('https');

function sendFCMNotification(accessToken, topic, callData) {
  const data = JSON.stringify({
    message: {
      topic: topic,
      data: {
        type: 'call',
        connectionId: callData.connectionId,
        callId: callData.callId || callData.connectionId,
        media: callData.media || 'audio',
        duration: callData.duration || '0',
        bookingId: callData.bookingId ? String(callData.bookingId) : '0',
        host: callData.host || '',
        username: callData.username || 'Unknown',
        secret: callData.secret || '',
        from: callData.from || callData.username || 'Unknown'
      }
    }
  });

  const options = {
    hostname: 'fcm.googleapis.com',
    path: `/v1/projects/YOUR_PROJECT_ID/messages:send`,
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${accessToken}`,
      'Content-Type': 'application/json',
      'Content-Length': data.length
    }
  };

  const req = https.request(options, (res) => {
    console.log(`Status Code: ${res.statusCode}`);
    res.on('data', (d) => {
      process.stdout.write(d);
    });
  });

  req.on('error', (error) => {
    console.error(error);
  });

  req.write(data);
  req.end();
}
```

### FCM Token Management

The plugin uses FCM topic subscription, which doesn't require managing individual device tokens. However, if you need the FCM token for other purposes, you can retrieve it:

```typescript
import { PushNotifications } from '@capacitor/push-notifications';

async function getFCMToken() {
  const result = await PushNotifications.register();
  return result.value;
}
```

## Permissions

### Required Permissions

The following permissions are automatically declared by the plugin:

| Permission | Purpose | Required For |
|------------|---------|--------------|
| `CALL_PHONE` | Make phone calls | Call functionality |
| `DISABLE_KEYGUARD` | Disable lock screen | Answer calls when locked |
| `MANAGE_OWN_CALLS` | Manage own calls | Call management |
| `INTERNET` | Network access | FCM and API calls |
| `VIBRATE` | Vibration | Call notifications |

### Runtime Permission Handling

For Android 6.0+, handle runtime permissions:

```typescript
import { Permissions } from '@capacitor/core';

async function checkAndRequestPermissions() {
  const phonePermission = await Permissions.query({ name: 'phone' });
  
  if (phonePermission.state !== 'granted') {
    const result = await Permissions.request({ name: 'phone' });
    if (result.state !== 'granted') {
      console.error('Phone permission denied');
      return false;
    }
  }
  
  if (Capacitor.getPlatform() === 'android' && 
      parseInt(Capacitor.getPlatformVersion()) >= 33) {
    const notificationPermission = await Permissions.query({ 
      name: 'notifications' 
    });
    
    if (notificationPermission.state !== 'granted') {
      await Permissions.request({ name: 'notifications' });
    }
  }
  
  return true;
}
```

## Troubleshooting

### FCM Messages Not Received

**Symptoms**: Incoming calls not showing up

**Solutions**:
1. Verify `google-services.json` is in `android/app/` directory
2. Check that google-services plugin is applied in `android/app/build.gradle`
3. Verify FCM topic subscription succeeded (check Logcat for "Subscribed" message)
4. Ensure using `data` payload, not `notification` payload
5. Check Firebase Console for message delivery status
6. Verify app has internet permission and connectivity

**Debug Steps**:
```bash
adb logcat | grep -E "CallKitVoip|MyFirebaseMsgService|FCM"
```

### Call UI Not Showing

**Symptoms**: FCM message received but no call UI appears

**Solutions**:
1. Check that `MyConnectionService` is properly registered in manifest
2. Verify app has `MANAGE_OWN_CALLS` permission
3. Check Logcat for ConnectionService errors
4. Ensure device is running Android 6.0+ (API 23+)
5. Verify phone account is registered (check TelecomManager)

**Debug Steps**:
```bash
adb logcat | grep -E "MyConnectionService|TelecomManager"
```

### Build Errors

**Symptoms**: Gradle build fails

**Common Issues**:

1. **Missing google-services.json**
   - Ensure file exists at `android/app/google-services.json`
   - Verify file is not corrupted

2. **Package name mismatch**
   - Ensure package name in `google-services.json` matches your app's package name
   - Check `AndroidManifest.xml` package attribute

3. **Dependency conflicts**
   - Clean and rebuild: `./gradlew clean build`
   - Check for version conflicts in `build.gradle`

4. **Gradle version issues**
   - Ensure using Gradle 8.0+ for Android Gradle Plugin 8.0.0
   - Update `gradle-wrapper.properties` if needed

**Fix Commands**:
```bash
cd android
./gradlew clean
./gradlew build
```

### App Crashes on Registration

**Symptoms**: App crashes when calling `register()`

**Solutions**:
1. Verify Firebase is properly initialized
2. Check that `google-services.json` is valid
3. Ensure internet permission is granted
4. Check Logcat for specific error messages

### Calls Not Answering

**Symptoms**: Call UI shows but answering doesn't work

**Solutions**:
1. Verify event listeners are properly set up
2. Check that `callAnswered` event is being triggered
3. Ensure app is handling the event correctly
4. Check Logcat for ConnectionService errors

### Background Call Handling

**Symptoms**: Calls not received when app is in background or killed

**Solutions**:
1. Ensure `MyFirebaseMessagingService` is properly registered
2. Check that FCM high-priority messages are being sent
3. Verify device battery optimization is disabled for your app
4. Test with app in different states (foreground, background, killed)

**Battery Optimization**:
```typescript
import { App } from '@capacitor/app';

async function disableBatteryOptimization() {
  if (Capacitor.getPlatform() === 'android') {
    await App.openUrl({
      url: 'android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS'
    });
  }
}
```

## API Reference

### Methods

#### `register(options)`

Registers the device for VoIP calls by subscribing to an FCM topic.

**Parameters:**
```typescript
{
  userToken: string;  // Required: Unique user identifier used as FCM topic
}
```

**Returns:** `Promise<void>`

**Example:**
```typescript
await CallKitVoip.register({ userToken: 'user-123' });
```

#### `addListener(eventName, listenerFunc)`

Adds an event listener for VoIP events.

**Parameters:**
- `eventName`: `'registration' | 'callAnswered' | 'callRejected' | 'callEnded'`
- `listenerFunc`: Callback function

**Returns:** `PluginListenerHandle`

**Example:**
```typescript
const handle = await CallKitVoip.addListener('callAnswered', (data) => {
  console.log('Call answered:', data);
});

handle.remove();
```

### Events

#### `registration`

Fired when device successfully registers for VoIP notifications.

**Data:**
```typescript
{
  value: string;  // FCM token or registration identifier
}
```

#### `callAnswered`

Fired when user answers an incoming call.

**Data:**
```typescript
{
  callId: string;        // Call identifier
  media: string;         // Media type ("audio" or "video")
  duration: string;      // Call duration in seconds
  bookingId: number;     // Booking identifier
  host: string;          // SIP host or server address
  username: string;      // Caller's display name
  secret: string;        // Secret key for authentication
}
```

#### `callRejected`

Fired when user rejects an incoming call.

**Data:**
```typescript
{
  callId: string;        // Call identifier
  media: string;         // Media type ("audio" or "video")
  duration: string;      // Call duration in seconds
  bookingId: number;     // Booking identifier
  host: string;          // SIP host or server address
  username: string;      // Caller's display name
  secret: string;        // Secret key for authentication
}
```

#### `callEnded`

Fired when a call ends.

**Data:**
```typescript
{
  callId: string;        // Call identifier
  media: string;         // Media type ("audio" or "video")
  duration: string;      // Call duration in seconds
  bookingId: number;     // Booking identifier
  host: string;          // SIP host or server address
  username: string;      // Caller's display name
  secret: string;        // Secret key for authentication
}
```

### Types

```typescript
interface CallToken {
  value: string;
}

interface CallData {
  callId: string;
  media: string;
  duration: string;
  bookingId: number;
  host: string;
  username: string;
  secret: string;
}
```

## Testing

### Testing Checklist

- [ ] Firebase project created and configured
- [ ] `google-services.json` added to `android/app/`
- [ ] google-services plugin applied in app `build.gradle`
- [ ] App builds successfully
- [ ] FCM topic subscription works
- [ ] Incoming FCM messages trigger call UI
- [ ] Call answer/reject works
- [ ] Events are properly fired
- [ ] App handles calls when in foreground
- [ ] App handles calls when in background
- [ ] App handles calls when killed
- [ ] Permissions are properly requested
- [ ] Battery optimization doesn't block calls

### Testing FCM Messages

Use Firebase Console or a test script to send FCM messages:

```javascript
const admin = require('firebase-admin');

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount)
});

async function testCall(userToken) {
  await admin.messaging().send({
    topic: userToken,
    data: {
      type: 'call',
      connectionId: 'test-' + Date.now(),
      callId: 'test-' + Date.now(),
      media: 'audio',
      duration: '0',
      bookingId: '12345',
      host: 'sip.example.com',
      username: 'Test Caller',
      secret: 'test-secret',
      from: '+1234567890'
    }
  });
}
```

## Additional Notes

- The plugin uses Android's native `ConnectionService` API for call UI integration
- FCM topic subscription allows multiple devices per user
- The plugin automatically handles call state management
- Foreground service ensures call UI remains active
- Background service handles FCM-triggered calls
- ConnectionService requires Android 6.0+ (API 23) for full functionality

## Support

For issues or questions:
- Check the [GitHub Issues](https://github.com/kin9aziz/capacitor-plugin-callkit-voip.git/issues)
- Review Firebase Cloud Messaging documentation
- Check Android ConnectionService documentation
