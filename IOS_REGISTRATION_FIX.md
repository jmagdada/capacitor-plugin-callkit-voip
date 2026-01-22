# Registration Event Fix (iOS & Android)

## Issue

The `registration` event listener is not capturing the VoIP/FCM token on iOS and Android because of a **timing race condition**.

### What's Happening

**iOS**: When you call `CallKitVoip.register()`, PushKit immediately calls the delegate method `pushRegistry(_:didUpdate:for:)` which emits the `registration` event.

**Android**: When you call `CallKitVoip.register()`, Firebase Messaging gets the FCM token and immediately emits the `registration` event.

On both platforms, if your listener is added **after** the `register()` call, the event has already fired and your callback misses it.

```typescript
CallKitVoip.addListener('registration', (token: CallToken) => {
  console.log('📱 VoIP token received:', token.value)
})

await CallKitVoip.register({ userToken: 'xxx' })
```

Even though the code looks correct, the event might fire during the `register()` call but before the Promise resolves, causing the listener to miss it.

## Solutions

### Solution 1: Add Listener BEFORE Calling Register (Recommended)

Always add your listener before calling `register()`:

```typescript
CallKitVoip.addListener('registration', (token: CallToken) => {
  console.log('📱 VoIP token received:', token.value)
})

await CallKitVoip.register({ userToken: 'xxx' })
```

### Solution 2: Use the New `getVoipToken()` Method

As of this version, the plugin now caches the VoIP token. You can retrieve it at any time using the new `getVoipToken()` method:

```typescript
await CallKitVoip.register({ userToken: 'xxx' })

try {
  const token = await CallKitVoip.getVoipToken()
  console.log('📱 VoIP token:', token.value)
} catch (error) {
  console.error('Token not available yet')
}
```

### Solution 3: Combined Approach (Most Reliable)

Use both the listener (for updates) and the getter (for initial retrieval):

```typescript
CallKitVoip.addListener('registration', (token: CallToken) => {
  console.log('📱 VoIP token updated:', token.value)
  sendTokenToServer(token.value)
})

await CallKitVoip.register({ userToken: 'xxx' })

try {
  const token = await CallKitVoip.getVoipToken()
  console.log('📱 Initial VoIP token:', token.value)
  sendTokenToServer(token.value)
} catch (error) {
  console.log('Waiting for token via listener...')
}
```

## What Changed

### iOS Plugin Changes

1. **Added token caching**: The plugin now stores the VoIP token when received
2. **Added `getVoipToken()` method**: Allows retrieving the cached token at any time

### Android Plugin Changes

1. **Added token caching**: The plugin now stores the FCM token when received
2. **Added `getVoipToken()` method**: Allows retrieving the cached token at any time

### TypeScript Interface Changes

Added new method to `CallKitVoipPlugin`:

```typescript
getVoipToken(): Promise<CallToken>;
```

## Migration Guide

If your existing code wasn't receiving the `registration` event:

**Before:**
```typescript
await CallKitVoip.register({ userToken: 'xxx' })

CallKitVoip.addListener('registration', (token: CallToken) => {
  console.log('📱 VoIP token received:', token.value)
})
```

**After (Option 1):**
```typescript
CallKitVoip.addListener('registration', (token: CallToken) => {
  console.log('📱 VoIP token received:', token.value)
})

await CallKitVoip.register({ userToken: 'xxx' })
```

**After (Option 2):**
```typescript
await CallKitVoip.register({ userToken: 'xxx' })

const token = await CallKitVoip.getVoipToken()
console.log('📱 VoIP token:', token.value)
```

## Testing

To verify the fix works:

### iOS
1. Build and install your app on a real iOS device (VoIP doesn't work in simulator)
2. Open the app and trigger the registration
3. Check your logs for the VoIP token
4. If using the listener, you should see "📱 VoIP token received: [token]"
5. If using `getVoipToken()`, you should receive the token in the Promise

### Android
1. Build and install your app on a device or emulator with Google Play Services
2. Ensure Firebase is properly configured
3. Open the app and trigger the registration
4. Check your logs for the FCM token
5. If using the listener, you should see "📱 VoIP token received: [token]"
6. If using `getVoipToken()`, you should receive the token in the Promise

## Notes

### iOS
- VoIP push notifications only work on real iOS devices, not simulators
- The token may change when the app is reinstalled or after system updates
- The `registration` listener will still fire when the token is updated
- The cached token persists for the lifetime of the app session only

### Android
- FCM tokens work on both emulators and real devices (with Google Play Services)
- The token may change when the app is reinstalled or after Firebase updates
- The `registration` listener will still fire when the token is refreshed
- The cached token persists for the lifetime of the app session only

