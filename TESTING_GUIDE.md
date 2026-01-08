# Testing Guide - Android CallKit VoIP Fixes

## Prerequisites

1. **Device Setup**
   - Android device (physical preferred, emulator works but limited)
   - Android 6.0+ (API 23+)
   - USB debugging enabled
   - ADB installed on development machine

2. **App Setup**
   - App built and installed on device
   - Firebase configured with valid `google-services.json`
   - PhoneAccount enabled in Settings → Calls → Calling accounts

3. **Tools**
   - Terminal with ADB access
   - Firebase Console access (to send test messages)
   - Logcat viewer

---

## Test Scenario 1: Lock Screen / Device Closed

### Goal
Verify that push notifications wake the device and display properly on lock screen.

### Steps

#### 1.1 Lock Screen - Standard Test
```bash
# 1. Lock device (press power button)
# 2. Wait 5 seconds
# 3. Send FCM test message from server or Firebase Console

# Expected Results:
# - Device wakes up immediately
# - Screen turns on (bright)
# - Notification appears on lock screen
# - Shows caller name and buttons (Answer/Reject)
# - Device vibrates
# - Ringtone plays
```

#### 1.2 Device Asleep - Extended Test
```bash
# 1. Lock device and wait for screen to timeout
# 2. Wait additional 2 minutes (deep sleep)
# 3. Send FCM message

# Expected Results:
# - Device wakes from deep sleep
# - Screen turns on within 1-2 seconds
# - Same behavior as 1.1
```

#### 1.3 Do Not Disturb - Priority Test
```bash
# 1. Enable Do Not Disturb mode
# 2. Lock device
# 3. Send FCM message

# Expected Results:
# - Notification still appears (bypasses DND)
# - Device still wakes
# - All functionality works
```

### Verification Commands
```bash
# Check wake lock is acquired
adb shell dumpsys power | grep CallKitVoip

# Check notification is posted
adb shell dumpsys notification | grep VoipForeground

# Monitor logs
adb logcat | grep -E "VoipForegroundService|WakeLock"
```

### Pass Criteria
- ✅ Device wakes within 2 seconds
- ✅ Screen turns on bright (not dim)
- ✅ Notification visible on lock screen
- ✅ Can interact with buttons without unlocking
- ✅ No "WakeLock error" in logs

---

## Test Scenario 2: App Quit - Wake Up on Answer

### Goal
Verify that clicking answer button launches the app when it's been force quit.

### Steps

#### 2.1 Force Quit - Answer Test
```bash
# 1. Open app and register for VoIP
# 2. Force quit app (Recent Apps → Swipe away)
# 3. Verify app is not running: adb shell ps | grep [your.package.name]
# 4. Send FCM message
# 5. Tap "Answer" button on notification

# Expected Results:
# - Notification appears
# - Clicking "Answer" launches app
# - App opens to appropriate screen
# - App receives call data via intent extras
# - ConnectionService activates
```

#### 2.2 Background - Answer Test
```bash
# 1. Open app and register
# 2. Press home button (app in background)
# 3. Send FCM message
# 4. Tap "Answer" button

# Expected Results:
# - App comes to foreground
# - Call screen displays
# - Audio connects properly
```

#### 2.3 Notification Body Tap Test
```bash
# 1. Force quit app
# 2. Send FCM message
# 3. Tap notification body (not buttons)

# Expected Results:
# - App launches
# - Shows incoming call screen
# - Can still answer/reject
```

### Verification Commands
```bash
# Check if app is running
adb shell ps | grep [your.package.name]

# Monitor app launch
adb logcat | grep -E "START.*[your.package.name]|launchApp|RECEIVE_CALL"

# Check intent extras received
adb logcat | grep -E "connectionId|callAnswered"
```

### Pass Criteria
- ✅ App launches when answer clicked (quit state)
- ✅ App brought to front (background state)
- ✅ Intent extras received correctly
- ✅ No crash on launch
- ✅ Connection properly established

### Debug Failed Launch
```bash
# If app doesn't launch, check:
adb logcat | grep -E "ActivityManager|CallKitVoip"

# Common issues:
# - App not whitelisted for battery optimization
# - Launch intent null (check package name)
# - Permission denied (check SYSTEM_ALERT_WINDOW)
```

---

## Test Scenario 3: Active App - Reject Doesn't Quit

### Goal
Verify that rejecting a call while app is active doesn't cause app to quit.

### Steps

#### 3.1 Foreground Reject Test
```bash
# 1. Open app to main screen
# 2. Keep app in foreground
# 3. Send FCM message
# 4. Notification appears (or native UI)
# 5. Click "Reject" button

# Expected Results:
# - Call is rejected
# - App remains in foreground
# - App does not quit or crash
# - User stays on current screen
# - No unexpected navigation
```

#### 3.2 Multiple Reject Test
```bash
# 1. App in foreground
# 2. Send FCM message
# 3. Reject call
# 4. Send another FCM message immediately
# 5. Reject second call

# Expected Results:
# - Both calls rejected properly
# - App stable throughout
# - No memory leaks
# - No duplicate notifications
```

#### 3.3 Reject During Navigation Test
```bash
# 1. App in foreground
# 2. Navigate to different screen
# 3. While navigating, send FCM
# 4. Reject call immediately

# Expected Results:
# - Navigation completes normally
# - App doesn't quit
# - No state corruption
```

### Verification Commands
```bash
# Monitor for crashes
adb logcat | grep -E "FATAL|AndroidRuntime|CANCEL_CALL"

# Check app activity
adb shell dumpsys activity [your.package.name]

# Monitor broadcasts (should NOT see ACTION_CLOSE_SYSTEM_DIALOGS)
adb logcat | grep "CLOSE_SYSTEM_DIALOGS"
```

### Pass Criteria
- ✅ App remains active after reject
- ✅ No crash or force close
- ✅ No "ACTION_CLOSE_SYSTEM_DIALOGS" in logs
- ✅ User stays on current screen
- ✅ App state preserved
- ✅ Multiple rejects handled gracefully

### Debug App Quit
```bash
# If app quits unexpectedly:
adb logcat | grep -B 10 "Force finishing|Process.*died"

# Check for:
# - System dialogs broadcast (shouldn't exist now)
# - Uncaught exceptions
# - Activity lifecycle issues
```

---

## Cross-Scenario Integration Tests

### Test 4: Full Call Flow - Lock to Answer
```bash
# Complete flow test
1. Force quit app
2. Lock device
3. Wait 1 minute
4. Send FCM message
5. Device wakes
6. Click "Answer"
7. App launches
8. Verify audio connection

# Expected: Complete successful call flow
```

### Test 5: Rapid Answer/Reject
```bash
# Stress test
1. App in any state
2. Send FCM
3. Quickly reject
4. Send another FCM
5. Quickly answer

# Expected: No crashes, proper state handling
```

### Test 6: Boot Persistence
```bash
# Reboot test
1. Register PhoneAccount
2. Verify working: adb shell dumpsys telecom | grep "VoIP Account"
3. Reboot device: adb reboot
4. Wait for boot complete
5. Check PhoneAccount: adb shell dumpsys telecom | grep "VoIP Account"
6. Send FCM message

# Expected: PhoneAccount re-registered, calls work
```

---

## Device-Specific Tests

### Test 7: Manufacturer Variations

#### Samsung Devices
```bash
# Additional checks:
- Settings → Apps → [YourApp] → Battery → Unrestricted
- Settings → Device care → Battery → App power management → Add to exception
```

#### Xiaomi Devices
```bash
# Additional checks:
- Settings → Apps → Manage apps → [YourApp] → Autostart: Enable
- Settings → Battery & performance → App battery saver → No restrictions
```

#### Huawei Devices
```bash
# Additional checks:
- Settings → Battery → App launch → [YourApp] → Manage manually
- Enable all three options (Auto-launch, Secondary launch, Run in background)
```

#### OnePlus Devices
```bash
# Additional checks:
- Settings → Battery → Battery optimization → [YourApp] → Don't optimize
```

---

## Automated Testing Commands

### Quick Test Script
```bash
#!/bin/bash

echo "=== Android CallKit VoIP Test Suite ==="

# Test 1: Wake Lock Check
echo "Test 1: Checking wake lock capability..."
adb shell dumpsys power | grep -q "Wake" && echo "✅ Wake lock supported" || echo "❌ Wake lock not found"

# Test 2: Notification Channel
echo "Test 2: Checking notification channel..."
adb shell dumpsys notification | grep -q "IncomingCallChannel" && echo "✅ Channel created" || echo "❌ Channel missing"

# Test 3: PhoneAccount
echo "Test 3: Checking PhoneAccount registration..."
adb shell dumpsys telecom | grep -q "VoIP Account" && echo "✅ PhoneAccount registered" || echo "❌ PhoneAccount not found"

# Test 4: Permissions
echo "Test 4: Checking critical permissions..."
adb shell dumpsys package [your.package.name] | grep -E "WAKE_LOCK|USE_FULL_SCREEN_INTENT"

echo "=== Tests Complete ==="
```

### Send Test FCM Message
```javascript
// Node.js script using Firebase Admin SDK
const admin = require('firebase-admin');

async function sendTestCall(userToken) {
  const message = {
    data: {
      type: 'call',
      connectionId: 'test-' + Date.now(),
      username: 'Test Caller',
      from: '+1234567890',
      callId: 'call-' + Date.now(),
      media: 'audio',
      duration: '0',
      bookingId: '0',
      host: '',
      secret: ''
    },
    topic: userToken,
    android: {
      priority: 'high'
    }
  };

  const response = await admin.messaging().send(message);
  console.log('Test message sent:', response);
}

sendTestCall('user-123');
```

---

## Expected Logs

### Successful Wake Lock Acquisition
```
VoipForegroundService: onCreate called
VoipForegroundService: WakeLock acquired
VoipForegroundService: build_incoming_call_notification for Test Caller
```

### Successful App Launch
```
VoipActionReceiver: action: RECEIVE_CALL, username: Test Caller
VoipActionReceiver: Connection set to ACTIVE via notification answer button
VoipActionReceiver: Launched app for incoming call
ActivityManager: START u0 {flg=0x14000000 cmp=[your.package.name]/.MainActivity (has extras)}
```

### Successful Reject (No Quit)
```
VoipActionReceiver: action: CANCEL_CALL
MyConnectionService: Connection rejected and destroyed
VoipActionReceiver: endCall for username: Test Caller
[NO "ACTION_CLOSE_SYSTEM_DIALOGS" should appear]
[NO "Force finishing activity" should appear]
```

---

## Troubleshooting

### Issue: Device doesn't wake
**Check:**
1. WAKE_LOCK permission granted
2. Wake lock actually acquired (check logs)
3. Battery optimization disabled
4. High priority FCM message

**Solution:**
```bash
adb shell dumpsys power | grep CallKitVoip
# Should show wake lock details
```

### Issue: App doesn't launch
**Check:**
1. Package name correct
2. Launch intent not null
3. SYSTEM_ALERT_WINDOW granted
4. Not in background restriction

**Solution:**
```bash
adb logcat | grep "launchApp"
# Check for errors in launch
```

### Issue: App quits on reject
**Check:**
1. ACTION_CLOSE_SYSTEM_DIALOGS removed
2. No uncaught exceptions
3. Activity not finishing

**Solution:**
```bash
adb logcat | grep "CLOSE_SYSTEM_DIALOGS"
# Should return nothing
```

---

## Test Report Template

```markdown
## Test Results - [Date]

### Device Information
- Model: [e.g., Samsung Galaxy S21]
- Android Version: [e.g., Android 13]
- Build Number: [e.g., xxxxx]

### Test 1: Lock Screen Wake
- [ ] Device wakes: PASS / FAIL
- [ ] Screen turns on: PASS / FAIL
- [ ] Notification shows: PASS / FAIL
- Notes: _______

### Test 2: App Launch
- [ ] Launches when quit: PASS / FAIL
- [ ] Receives intent data: PASS / FAIL
- [ ] Audio connects: PASS / FAIL
- Notes: _______

### Test 3: Reject No Quit
- [ ] App stays active: PASS / FAIL
- [ ] No crash: PASS / FAIL
- [ ] Multiple rejects work: PASS / FAIL
- Notes: _______

### Overall Result: PASS / FAIL
```

---

## Performance Benchmarks

### Expected Timings
- Wake from sleep: < 2 seconds
- Notification display: < 500ms
- App launch: < 3 seconds
- Audio connection: < 5 seconds total

### Monitor Performance
```bash
adb logcat -v time | grep -E "VoipForeground|ConnectionService"
```

---

## Success Criteria Summary

✅ All three issues fixed:
1. Device wakes on lock screen
2. App launches when answer clicked
3. App doesn't quit on reject

✅ No regressions:
- Existing functionality still works
- No new crashes
- Performance acceptable

✅ Cross-device compatibility:
- Works on major manufacturers
- Android 6.0 - 14+ supported
- Both ARM and x86 architectures

