# Quick Changes Summary

## Issue 1: Lock Screen / Device Closed - No Push Reception

### Changes Made:
**VoipForegroundService.java**
- Added imports: `PowerManager`, `KeyguardManager`
- Added fields: `wakeLock`, `keyguardLock`
- Added `acquireWakeLock()` method - Wakes device with 60s timeout
- Added `releaseWakeLock()` method - Releases wake lock on destroy
- Called `acquireWakeLock()` in `onCreate()`
- Called `releaseWakeLock()` in `onDestroy()`
- Added `.setVisibility(VISIBILITY_PUBLIC)` to notification
- Updated notification channel with lock screen settings

**AndroidManifest.xml**
- Added `WAKE_LOCK` permission
- Added `SYSTEM_ALERT_WINDOW` permission
- Added `TURN_SCREEN_ON` permission
- Added `ACCESS_NOTIFICATION_POLICY` permission
- Added `android:directBootAware="true"` to FCM service
- Added `android:stopWithTask="false"` to FCM service

**BootReceiver.java (NEW)**
- Handles `BOOT_COMPLETED` and `LOCKED_BOOT_COMPLETED`
- Re-registers PhoneAccount after boot

**CallKitVoipPlugin.java**
- Added `initializePhoneAccountIfNeeded()` static method

---

## Issue 2: App Quit - No Wake Up on Answer Click

### Changes Made:
**VoipForegroundServiceActionReceiver.java**
- Added `launchApp()` method:
  ```java
  private void launchApp(Context context, String connectionId, String username) {
      Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
      launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | 
                           Intent.FLAG_ACTIVITY_CLEAR_TOP | 
                           Intent.FLAG_ACTIVITY_SINGLE_TOP);
      launchIntent.putExtra("connectionId", connectionId);
      launchIntent.putExtra("username", username);
      launchIntent.putExtra("callAnswered", true);
      context.startActivity(launchIntent);
  }
  ```
- Called `launchApp()` in `RECEIVE_CALL` action
- Called `launchApp()` in `FULLSCREEN_CALL` action

**VoipForegroundService.java**
- Added content intent to notification:
  ```java
  Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
  PendingIntent contentIntent = PendingIntent.getActivity(...);
  notificationBuilder.setContentIntent(contentIntent);
  ```

---

## Issue 3: Active App - Reject Makes App Quit

### Changes Made:
**VoipForegroundServiceActionReceiver.java**
- **REMOVED** these lines from `CANCEL_CALL` action:
  ```java
  Intent it = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
  context.sendBroadcast(it);
  ```
- Kept proper cleanup: `stopService()`, `setDisconnected()`, `destroy()`

---

## Files Changed

1. ✅ `VoipForegroundService.java` - Wake lock, app launch intent
2. ✅ `VoipForegroundServiceActionReceiver.java` - App launch, removed quit broadcast
3. ✅ `AndroidManifest.xml` - Permissions and boot receiver
4. ✅ `CallKitVoipPlugin.java` - Boot initialization support
5. ✅ `BootReceiver.java` - NEW FILE - Boot handling

---

## Testing Quick Guide

### Test 1: Lock Screen
```bash
# Lock device, send FCM message
# Expected: Device wakes, screen on, notification shows
```

### Test 2: App Quit
```bash
# Force quit app, send FCM, click answer
# Expected: App launches, call data received
```

### Test 3: Active Reject
```bash
# Open app, receive call, click reject
# Expected: App stays open, call ends, no crash
```

---

## Permissions to Request at Runtime

```typescript
await CallKitVoip.requestNotificationPermission(); // Android 13+
await CallKitVoip.requestPhoneNumbersPermission();
```

Users may also need to manually:
- Disable battery optimization
- Grant "Display over other apps"
- Enable PhoneAccount in Settings → Calls

