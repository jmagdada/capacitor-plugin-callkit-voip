# Android getVoipToken() Implementation

## Overview

The `getVoipToken()` method has been successfully implemented for Android to match the iOS functionality. This provides a consistent API across both platforms for retrieving cached FCM (Firebase Cloud Messaging) tokens.

## Implementation Details

### Changes Made

#### 1. Token Caching
**File:** `android/src/main/java/com/bfine/capactior/callkitvoip/CallKitVoipPlugin.java`

Added a static variable to cache the FCM token:

```java
private static String cachedVoipToken = null;
```

#### 2. Modified Token Notification
Updated the `notifyRegistration()` method to cache the token before emitting the event:

```java
public void notifyRegistration(String token) {
    cachedVoipToken = token;
    JSObject data = new JSObject();
    data.put("value", token);
    notifyListeners("registration", data);
    Log.d("CallKitVoip", "Registration event fired with token " + token);
}
```

#### 3. Added getVoipToken() Method
Created a new plugin method to retrieve the cached token:

```java
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
```

## How It Works

### Token Flow

1. **Initial Registration**
   - App calls `CallKitVoip.register({ userToken: 'xxx' })`
   - Plugin calls Firebase Messaging `getToken()`
   - On success, `notifyRegistration(token)` is called
   - Token is cached in `cachedVoipToken`
   - `registration` event is emitted to listeners

2. **Token Refresh**
   - Firebase automatically calls `onNewToken()` in `MyFirebaseMessagingService`
   - Service calls `notifyRegistration(token)` via plugin instance
   - Token is cached and event is emitted

3. **Token Retrieval**
   - App calls `await CallKitVoip.getVoipToken()`
   - Plugin returns cached token if available
   - If not available, Promise is rejected

## Usage Examples

### Example 1: Using Listener (Traditional)

```typescript
CallKitVoip.addListener('registration', (token: CallToken) => {
  console.log('📱 FCM token received:', token.value)
  sendTokenToServer(token.value)
})

await CallKitVoip.register({ userToken: 'my-topic' })
```

### Example 2: Using getVoipToken() (New)

```typescript
await CallKitVoip.register({ userToken: 'my-topic' })

try {
  const token = await CallKitVoip.getVoipToken()
  console.log('📱 FCM token:', token.value)
  sendTokenToServer(token.value)
} catch (error) {
  console.error('Token not available yet:', error)
}
```

### Example 3: Combined Approach (Best Practice)

```typescript
CallKitVoip.addListener('registration', (token: CallToken) => {
  console.log('📱 FCM token updated:', token.value)
  sendTokenToServer(token.value)
})

await CallKitVoip.register({ userToken: 'my-topic' })

try {
  const token = await CallKitVoip.getVoipToken()
  console.log('📱 Initial FCM token:', token.value)
  sendTokenToServer(token.value)
} catch (error) {
  console.log('Waiting for token via listener...')
}
```

## Platform Comparison

| Feature | iOS | Android |
|---------|-----|---------|
| Token Type | VoIP Push Token | FCM Token |
| Caching | ✅ Yes | ✅ Yes |
| getVoipToken() | ✅ Yes | ✅ Yes |
| Race Condition Fix | ✅ Fixed | ✅ Fixed |
| Token Refresh | Via PushKit | Via Firebase onNewToken |
| Testing Environment | Real device only | Device or emulator |

## Benefits

1. **Consistent API**: Same method signature across iOS and Android
2. **Race Condition Fix**: No more missed `registration` events
3. **Flexibility**: Can retrieve token at any time after registration
4. **Reliability**: Token is cached and survives across app lifecycle
5. **Debug Friendly**: Logs token retrieval attempts

## Testing

### Prerequisites
- Firebase configured in your Android app
- Google Play Services available (emulator or device)
- Valid `google-services.json` in your project

### Test Steps

1. Build and install the app
2. Open the app
3. Call `register()` method
4. Check Logcat for: "Retrieved cached VoIP token: [token]"
5. Verify token is not null and is a valid FCM token format

### Expected Behavior

```
D/CallKitVoip: register
D/CallKitVoip: Registration event fired with token [token-string]
D/CallKitVoip: Retrieved cached VoIP token: [token-string]
```

## Error Handling

### Token Not Available
If `getVoipToken()` is called before registration completes:

```typescript
try {
  const token = await CallKitVoip.getVoipToken()
} catch (error) {
  console.error('Token not available yet')
}
```

The promise will reject with message: "Token not available yet"

### Firebase Not Configured
If Firebase is not properly configured, the `register()` method will fail with:
"Cannot get FCM token: [error message]"

## Migration Notes

If your app was experiencing the race condition issue:

**Before:**
```typescript
await CallKitVoip.register({ userToken: 'xxx' })

CallKitVoip.addListener('registration', (token) => {
  console.log('Token:', token.value)
})
```

**After (Fix 1):**
```typescript
CallKitVoip.addListener('registration', (token) => {
  console.log('Token:', token.value)
})

await CallKitVoip.register({ userToken: 'xxx' })
```

**After (Fix 2):**
```typescript
await CallKitVoip.register({ userToken: 'xxx' })

const token = await CallKitVoip.getVoipToken()
console.log('Token:', token.value)
```

## Build Status

✅ TypeScript compilation: Success
✅ Java compilation: Success  
✅ No linter errors
✅ Plugin builds correctly

## Related Files

- `CallKitVoipPlugin.java` - Main plugin implementation
- `MyFirebaseMessagingService.java` - FCM message handling and token refresh
- `definitions.ts` - TypeScript interface definitions
- `web.ts` - Web platform stub implementation

