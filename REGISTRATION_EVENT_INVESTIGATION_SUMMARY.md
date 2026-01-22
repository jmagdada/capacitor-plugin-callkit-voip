# Registration Event Investigation Summary

## Problem

The `registration` listener callback was not being captured on iOS and Android when using:

```typescript
CallKitVoip.addListener('registration', (token: CallToken) => {
  console.log('📱 VoIP token received:', token.value)
})
```

## Root Cause

**Race Condition**: The `registration` event was firing before the listener was attached on both platforms.

### Flow Analysis

#### iOS
1. App calls `await CallKitVoip.register()`
2. iOS PushKit **immediately** generates token and calls `pushRegistry(_:didUpdate:for:)`
3. Plugin emits `registration` event via `notifyListeners()`
4. If listener is added **after** `register()` is called, the event is missed
5. Result: Callback never fires

#### Android
1. App calls `await CallKitVoip.register()`
2. Firebase Messaging **immediately** retrieves FCM token via `getToken()`
3. Plugin emits `registration` event via `notifyListeners()` in the success callback
4. If listener is added **after** `register()` is called, the event is missed
5. Result: Callback never fires

### Evidence

#### iOS
In `CallKitVoipPlugin.swift` line 132-138:

```swift
public func pushRegistry(_ registry: PKPushRegistry, didUpdate pushCredentials: PKPushCredentials, for type: PKPushType) {
    let parts = pushCredentials.token.map { String(format: "%02.2hhx", $0) }
    let token = parts.joined()
    print("Token: \(token)")
    cachedVoipToken = token
    notifyListeners("registration", data: ["value": token])
}
```

#### Android
In `CallKitVoipPlugin.java` line 251-252 & 394-399:

```java
.addOnSuccessListener(token -> {
    notifyRegistration(token);
    // ...
})

public void notifyRegistration(String token) {
    cachedVoipToken = token;
    JSObject data = new JSObject();
    data.put("value", token);
    notifyListeners("registration", data);
    Log.d("CallKitVoip", "Registration event fired with token " + token);
}
```

The event is emitted correctly on both platforms, but timing matters.

## Solution Implemented

### 1. Token Caching

#### iOS Plugin
Added `cachedVoipToken` property to store the token when received:

**File:** `ios/Plugin/CallKitVoipPlugin.swift`
- Added private var `cachedVoipToken: String?`
- Modified `pushRegistry(_:didUpdate:for:)` to cache token
- Added `getVoipToken()` method to retrieve cached token

#### Android Plugin
Added `cachedVoipToken` static property to store the token when received:

**File:** `android/src/main/java/com/bfine/capactior/callkitvoip/CallKitVoipPlugin.java`
- Added static var `cachedVoipToken: String`
- Modified `notifyRegistration()` to cache token
- Added `getVoipToken()` method to retrieve cached token

### 2. New API Method

Added `getVoipToken()` method to the plugin interface:

**File:** `src/definitions.ts`
```typescript
getVoipToken(): Promise<CallToken>;
```

**File:** `src/web.ts`
- Added web platform implementation (throws error as VoIP not supported on web)

### 3. Documentation Updates

**File:** `README.md`
- Added warning about listener order
- Updated usage example to show correct pattern
- Added `getVoipToken()` API documentation

**File:** `IOS_REGISTRATION_FIX.md` (NEW)
- Comprehensive guide explaining the issue
- Three different solution approaches
- Migration guide for existing code

## Usage Recommendations

### Option 1: Add Listener Before Register (Recommended)

```typescript
CallKitVoip.addListener('registration', (token: CallToken) => {
  console.log('📱 VoIP token received:', token.value)
})

await CallKitVoip.register({ userToken: 'xxx' })
```

### Option 2: Use getVoipToken()

```typescript
await CallKitVoip.register({ userToken: 'xxx' })

const token = await CallKitVoip.getVoipToken()
console.log('📱 VoIP token:', token.value)
```

### Option 3: Combined (Most Reliable)

```typescript
CallKitVoip.addListener('registration', (token: CallToken) => {
  console.log('📱 Token updated:', token.value)
})

await CallKitVoip.register({ userToken: 'xxx' })

try {
  const token = await CallKitVoip.getVoipToken()
  console.log('📱 Initial token:', token.value)
} catch {
  console.log('Waiting for token via listener...')
}
```

## Files Modified

1. `ios/Plugin/CallKitVoipPlugin.swift` - Added token caching and getter method
2. `android/src/main/java/com/bfine/capactior/callkitvoip/CallKitVoipPlugin.java` - Added token caching and getter method
3. `src/definitions.ts` - Added `getVoipToken()` interface
4. `src/web.ts` - Added web platform stub
5. `README.md` - Updated documentation and usage examples
6. `IOS_REGISTRATION_FIX.md` (NEW) - Detailed fix guide (renamed to reflect both platforms)

## Testing

Build completed successfully:
- TypeScript compilation: ✅
- Rollup bundle: ✅
- iOS Swift compilation: ✅
- Android Java compilation: ✅
- No linter errors: ✅

### To Test in Your App

#### iOS
1. Update the plugin in your project
2. Rebuild your iOS app
3. Test on a **real iOS device** (VoIP doesn't work in simulator)
4. Check console logs for the VoIP token

#### Android
1. Update the plugin in your project
2. Rebuild your Android app
3. Test on a device or emulator with Google Play Services
4. Check Logcat for the FCM token

## Next Steps for Your App

1. Update the plugin version in your project
2. Modify your registration code to add listeners before calling `register()`
3. Or use the new `getVoipToken()` method to retrieve the token after registration
4. Test on real devices (iOS requires real device, Android works on emulators)

## Additional Notes

### iOS
- VoIP push notifications only work on real iOS devices
- The token may change when app is reinstalled
- The cached token persists for the app session only
- The `registration` listener still fires when token updates

### Android
- FCM tokens work on devices and emulators with Google Play Services
- The token may change when app is reinstalled or Firebase updates
- The cached token persists for the app session only
- The `registration` listener still fires when token updates via `onNewToken()`

