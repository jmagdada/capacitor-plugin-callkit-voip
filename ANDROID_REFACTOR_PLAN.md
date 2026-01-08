# Android Refactor Plan - CallKit VoIP Plugin

**Date:** January 7, 2026  
**Version:** 1.0  
**Priority:** High

## Overview

This document outlines a comprehensive refactoring strategy to resolve the incoming call UI issues and improve the overall architecture of the Android CallKit VoIP plugin.

## Goals

### Primary Goals
1. ✅ Ensure incoming call UI always displays
2. ✅ Provide fallback mechanism when PhoneAccount is disabled
3. ✅ Improve user experience with clear guidance
4. ✅ Support Android 5.1 - 14+

### Secondary Goals
1. ✅ Simplify architecture and remove dead code
2. ✅ Improve code maintainability
3. ✅ Add comprehensive error handling
4. ✅ Enhance logging and debugging
5. ✅ Implement proper testing strategy

## Refactoring Strategy

### Phase 1: Critical Fixes (Week 1)
**Goal:** Get incoming calls working reliably

#### 1.1 Add In-App PhoneAccount Permission Alert
**Priority:** 🔴 CRITICAL  
**Effort:** 1 day  
**Impact:** HIGH - Resolves user confusion

**Problem:** Users don't know PhoneAccount needs to be enabled in Settings

**Key Insight:** ✅ While Android prevents automatic enabling (security requirement), we CAN:
- Detect when PhoneAccount is disabled
- Show in-app alert with clear instructions
- Deep link directly to Settings page
- Track when user completes the setup

**Solution:**

**Step 1: Create PhoneAccountHelper.java**

```java
package com.bfine.capactior.callkitvoip;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;

public class PhoneAccountHelper {
    private static final String TAG = "PhoneAccountHelper";
    
    public static boolean isPhoneAccountSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }
    
    public static boolean isPhoneAccountEnabled(Context context) {
        if (!isPhoneAccountSupported()) {
            return false;
        }
        
        try {
            TelecomManager tm = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
            PhoneAccountHandle handle = CallKitVoipPlugin.getPhoneAccountHandle();
            
            if (tm == null || handle == null) {
                return false;
            }
            
            PhoneAccount account = tm.getPhoneAccount(handle);
            return account != null && account.isEnabled();
            
        } catch (SecurityException e) {
            Log.w(TAG, "Permission denied checking PhoneAccount", e);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error checking PhoneAccount", e);
            return false;
        }
    }
    
    public static void openPhoneAccountSettings(Context context) {
        try {
            Intent intent = new Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Log.d(TAG, "Opened phone account settings");
        } catch (Exception e) {
            Log.e(TAG, "Cannot open phone account settings", e);
        }
    }
}
```

**Step 2: Add Methods to CallKitVoipPlugin.java**

```java
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
```

**Step 3: Update TypeScript Definitions (src/definitions.ts)**

```typescript
export interface CallKitVoipPlugin {
  register(options: { userToken: string }): Promise<void>;
  
  checkPhoneAccountStatus(): Promise<PhoneAccountStatus>;
  
  openPhoneAccountSettings(): Promise<void>;
  
  answerCall(options: { connectionId: string }): Promise<void>;
  rejectCall(options: { connectionId: string }): Promise<void>;
  hangupCall(options: { connectionId: string }): Promise<void>;
}

export interface PhoneAccountStatus {
  supported: boolean;
  enabled: boolean;
  message?: string;
  instructions?: string;
  canOpenSettings: boolean;
}
```

**Step 4: App Implementation Example**

```typescript
import { CallKitVoip } from 'capacitor-plugin-callkit-voip';
import { alertController, toastController } from '@ionic/vue';
import { App } from '@capacitor/app';

async function checkAndRequestPhoneAccountPermission() {
  try {
    const status = await CallKitVoip.checkPhoneAccountStatus();
    
    if (status.supported && !status.enabled) {
      const alert = await alertController.create({
        header: 'Enable Call Features',
        cssClass: 'phone-account-alert',
        message: `
          <div style="text-align: left;">
            <p>To receive incoming calls with the native Android UI, you need to enable the VoIP Account.</p>
            <br>
            <p><strong>Steps:</strong></p>
            <ol style="margin: 10px 0; padding-left: 20px;">
              <li>Tap "Open Settings" below</li>
              <li>Find "VoIP Account" in the list</li>
              <li>Toggle the switch to <strong>ON</strong></li>
              <li>Return to this app</li>
            </ol>
            <br>
            <p style="font-size: 0.9em; color: #666;">
              Note: This is a one-time setup required by Android for security.
            </p>
          </div>
        `,
        buttons: [
          {
            text: 'Skip for Now',
            role: 'cancel',
            cssClass: 'secondary',
            handler: () => {
              console.log('User skipped PhoneAccount setup - will use notification UI');
            }
          },
          {
            text: 'Open Settings',
            cssClass: 'primary',
            handler: async () => {
              try {
                await CallKitVoip.openPhoneAccountSettings();
                
                setTimeout(async () => {
                  const followUp = await alertController.create({
                    header: 'Quick Reminder',
                    message: 'Find "VoIP Account" in the list and toggle it ON, then return here.',
                    buttons: ['Got it']
                  });
                  await followUp.present();
                }, 1000);
              } catch (error) {
                console.error('Cannot open settings:', error);
              }
            }
          }
        ]
      });
      
      await alert.present();
      return false;
    }
    
    return status.enabled;
  } catch (error) {
    console.error('Error checking PhoneAccount status:', error);
    return false;
  }
}

async function initializeCallFeatures() {
  console.log('Initializing call features...');
  
  const isEnabled = await checkAndRequestPhoneAccountPermission();
  
  if (isEnabled) {
    console.log('PhoneAccount enabled - will use native call UI');
  } else {
    console.log('PhoneAccount not enabled - will use notification fallback UI');
  }
  
  await CallKitVoip.register({ userToken: 'user-123' });
}

App.addListener('appStateChange', async (state) => {
  if (state.isActive) {
    const status = await CallKitVoip.checkPhoneAccountStatus();
    
    if (status.enabled) {
      const toast = await toastController.create({
        message: '✓ Call features enabled successfully!',
        duration: 3000,
        color: 'success',
        position: 'top'
      });
      await toast.present();
    }
  }
});

initializeCallFeatures();
```

**Files to Modify:**
- [ ] Create: `android/src/main/java/com/bfine/capactior/callkitvoip/PhoneAccountHelper.java`
- [ ] Modify: `android/src/main/java/com/bfine/capactior/callkitvoip/CallKitVoipPlugin.java` - Add methods
- [ ] Modify: `src/definitions.ts` - Add TypeScript interface
- [ ] Modify: `src/index.ts` - Export new interface (if needed)

**Testing Checklist:**
- [ ] Test on device with PhoneAccount disabled → Alert shows
- [ ] Tap "Open Settings" → Settings app opens to correct screen
- [ ] Enable PhoneAccount in Settings → Return to app
- [ ] App detects enabled status → Success message shows
- [ ] Test on Android 5.1 → Alert shows "not supported" message
- [ ] Test on Android 6+ → Full functionality works
- [ ] Test "Skip for Now" → App continues without error
- [ ] Test multiple calls to checkPhoneAccountStatus() → No crashes

**Benefits:**
- ✅ Clear user guidance - no confusion
- ✅ One-tap to Settings - direct deep link
- ✅ Visual instructions - step-by-step guide
- ✅ Status tracking - knows when setup complete
- ✅ Graceful degradation - works with or without PhoneAccount
- ✅ Quick to implement - 1 day effort
- ✅ No breaking changes - additive only

#### 1.2 Implement Fallback UI Strategy
**Priority:** 🔴 CRITICAL

**Problem:** No UI shown when PhoneAccount is disabled

**Solution:**

Add intelligent fallback in MyFirebaseMessagingService:
```java
private void showIncomingCall(String connectionId, String username, String fromNumber) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        if (CallKitVoipPlugin.isPhoneAccountEnabled(getApplicationContext())) {
            // Use native ConnectionService
            showNativeIncomingCall(connectionId, username, fromNumber);
        } else {
            // Fall back to notification UI
            showNotificationIncomingCall(connectionId, username, fromNumber);
        }
    } else {
        // Android < 6.0 - use notification only
        showNotificationIncomingCall(connectionId, username, fromNumber);
    }
}

private void showNativeIncomingCall(...) {
    // Existing TelecomManager.addNewIncomingCall() logic
}

private void showNotificationIncomingCall(...) {
    Intent intent = new Intent(this, VoipForegroundService.class);
    intent.setAction("incoming");
    intent.putExtra("connectionId", connectionId);
    intent.putExtra("username", username);
    intent.putExtra("from", fromNumber);
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(intent);
    } else {
        startService(intent);
    }
}
```

**Files to Modify:**
- [ ] Modify: `MyFirebaseMessagingService.java` - Add fallback logic
- [ ] Modify: `VoipForegroundService.java` - Update to use connectionId
- [ ] Modify: `VoipForegroundServiceActionReceiver.java` - Fix roomName → connectionId

**Testing:**
- [ ] Test with PhoneAccount enabled → native UI
- [ ] Test with PhoneAccount disabled → notification UI
- [ ] Test on Android 5.1 → notification UI
- [ ] Test Answer/Reject on notification UI

#### 1.3 Add Missing Permissions
**Priority:** 🟡 HIGH

**Problem:** Missing permissions for Android 13+ and 14+

**Solution:**

Update AndroidManifest.xml:
```xml
<!-- Existing permissions -->
<uses-permission android:name="android.permission.CALL_PHONE"/>
<uses-permission android:name="android.permission.DISABLE_KEYGUARD" />
<uses-permission android:name="android.permission.MANAGE_OWN_CALLS"/>
<uses-permission android:name="android.permission.READ_PHONE_STATE"/>
<uses-permission android:name="android.permission.READ_PHONE_NUMBERS"/>
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.VIBRATE" />

<!-- Add these -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_PHONE_CALL"/>
<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT"/>
```

Update service declarations:
```xml
<service
    android:name="com.bfine.capactior.callkitvoip.androidcall.VoipForegroundService"
    android:enabled="true"
    android:exported="true"
    android:foregroundServiceType="phoneCall">
</service>
```

Add runtime permission request:
```java
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
```

**Files to Modify:**
- [ ] Modify: `AndroidManifest.xml` - Add permissions and service types
- [ ] Modify: `CallKitVoipPlugin.java` - Add notification permission request
- [ ] Modify: `definitions.ts` - Add TypeScript method

**Testing:**
- [ ] Test on Android 13+ - notification permission requested
- [ ] Test foreground service on Android 14+
- [ ] Test full screen intent works

### Phase 2: Architecture Cleanup (Week 2)
**Goal:** Simplify and improve code quality

#### 2.1 Remove or Repurpose VoipBackgroundService
**Priority:** 🟡 MEDIUM

**Problem:** Empty service serves no purpose

**Options:**

**Option A: Remove completely**
```java
// Delete VoipBackgroundService.java
// Remove from AndroidManifest.xml
```

**Option B: Repurpose for pre-Android 8 support**
```java
// Use for background call processing on older Android versions
```

**Recommendation:** Remove completely unless specific need identified

**Files to Modify:**
- [ ] Delete: `VoipBackgroundService.java`
- [ ] Modify: `AndroidManifest.xml` - Remove service declaration

#### 2.2 Fix VoipForegroundService Integration
**Priority:** 🟡 MEDIUM

**Problem:** Service uses wrong parameter names

**Solution:**

Update VoipForegroundService to use standard parameters:
```java
public void build_incoming_call_notification(Intent intent) {
    String connectionId = intent.getStringExtra("connectionId");  // Was: roomName
    String username = intent.getStringExtra("username");
    String from = intent.getStringExtra("from");
    
    // Store connectionId for button actions
    // ... rest of implementation
}
```

Update VoipForegroundServiceActionReceiver:
```java
private void performClickAction(Context context, String action, String username, String connectionId) {
    if (action.equals("RECEIVE_CALL")) {
        // Stop service
        context.stopService(new Intent(context, VoipForegroundService.class));
        
        // If PhoneAccount available, use it
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && 
            CallKitVoipPlugin.isPhoneAccountEnabled(context)) {
            // Trigger native call UI
            notifyCallAnswered(connectionId);
        } else {
            // Handle in app
            notifyCallAnswered(connectionId);
        }
    }
    // ... handle other actions
}
```

**Files to Modify:**
- [ ] Modify: `VoipForegroundService.java` - Parameter names
- [ ] Modify: `VoipForegroundServiceActionReceiver.java` - Integration logic

#### 2.3 Consolidate PhoneAccount Status Checking
**Priority:** 🟡 MEDIUM

**Problem:** PhoneAccount status checking is duplicated and complex

**Solution:**

Move all PhoneAccount logic to PhoneAccountHelper:
```java
public class PhoneAccountHelper {
    private static final String TAG = "PhoneAccountHelper";
    
    public static boolean isPhoneAccountSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }
    
    public static boolean isSelfManagedSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }
    
    public static boolean isPhoneAccountEnabled(Context context) {
        if (!isPhoneAccountSupported()) return false;
        
        try {
            TelecomManager tm = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
            PhoneAccountHandle handle = CallKitVoipPlugin.getPhoneAccountHandle();
            
            if (tm == null || handle == null) return false;
            
            PhoneAccount account = tm.getPhoneAccount(handle);
            return account != null && account.isEnabled();
            
        } catch (SecurityException e) {
            Log.w(TAG, "Permission denied checking PhoneAccount", e);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error checking PhoneAccount", e);
            return false;
        }
    }
    
    public static void openPhoneAccountSettings(Context context) {
        try {
            Intent intent = new Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Cannot open phone account settings", e);
        }
    }
}
```

**Files to Modify:**
- [ ] Create: `PhoneAccountHelper.java`
- [ ] Modify: `CallKitVoipPlugin.java` - Use PhoneAccountHelper
- [ ] Modify: `MyFirebaseMessagingService.java` - Use PhoneAccountHelper

#### 2.4 Improve Error Handling and Logging
**Priority:** 🟡 MEDIUM

**Problem:** Errors logged but not surfaced to JavaScript layer

**Solution:**

Add error event notification:
```java
public void notifyError(String errorCode, String errorMessage) {
    JSObject data = new JSObject();
    data.put("code", errorCode);
    data.put("message", errorMessage);
    notifyListeners("error", data);
    Log.e("CallKitVoip", "Error: " + errorCode + " - " + errorMessage);
}

// Usage
if (!isPhoneAccountEnabled) {
    notifyError("PHONE_ACCOUNT_DISABLED", 
        "PhoneAccount is not enabled. Please enable in Settings > Calls > Calling accounts");
}
```

Add error codes enum:
```java
public class CallKitError {
    public static final String PHONE_ACCOUNT_DISABLED = "PHONE_ACCOUNT_DISABLED";
    public static final String PERMISSION_DENIED = "PERMISSION_DENIED";
    public static final String TELECOM_SERVICE_UNAVAILABLE = "TELECOM_SERVICE_UNAVAILABLE";
    public static final String CONNECTION_FAILED = "CONNECTION_FAILED";
    public static final String INVALID_PAYLOAD = "INVALID_PAYLOAD";
}
```

**Files to Modify:**
- [ ] Create: `CallKitError.java`
- [ ] Modify: `CallKitVoipPlugin.java` - Add notifyError method
- [ ] Modify: `MyFirebaseMessagingService.java` - Use notifyError
- [ ] Modify: `MyConnectionService.java` - Use notifyError
- [ ] Modify: `definitions.ts` - Add error event type

### Phase 3: Enhancements (Week 3)
**Goal:** Improve user experience and reliability

#### 3.1 Add Connection Recovery
**Priority:** 🟢 LOW

**Problem:** Failed connections not retried

**Solution:**

Add retry logic with exponential backoff:
```java
public class CallConnectionManager {
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000;
    
    public void showIncomingCallWithRetry(
            String connectionId, 
            String username, 
            String fromNumber, 
            int retryCount) {
        
        try {
            showIncomingCall(connectionId, username, fromNumber);
        } catch (Exception e) {
            if (retryCount < MAX_RETRIES) {
                long delay = RETRY_DELAY_MS * (long) Math.pow(2, retryCount);
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    showIncomingCallWithRetry(connectionId, username, fromNumber, retryCount + 1);
                }, delay);
            } else {
                // Fallback to notification UI
                showNotificationIncomingCall(connectionId, username, fromNumber);
            }
        }
    }
}
```

**Files to Modify:**
- [ ] Create: `CallConnectionManager.java`
- [ ] Modify: `MyFirebaseMessagingService.java` - Use manager

#### 3.2 Add Call State Persistence
**Priority:** 🟢 LOW

**Problem:** Call state lost if app is killed

**Solution:**

Use SharedPreferences to persist active calls:
```java
public class CallStateManager {
    private static final String PREFS_NAME = "callkit_state";
    private static final String KEY_ACTIVE_CALLS = "active_calls";
    
    public void saveCallState(Context context, String connectionId, CallConfig config);
    public Map<String, CallConfig> restoreCallStates(Context context);
    public void clearCallState(Context context, String connectionId);
}
```

**Files to Modify:**
- [ ] Create: `CallStateManager.java`
- [ ] Modify: `CallKitVoipPlugin.java` - Restore on load
- [ ] Modify: `MyConnectionService.java` - Clear on disconnect

#### 3.3 Implement Call Quality Monitoring
**Priority:** 🟢 LOW

**Problem:** No visibility into call issues

**Solution:**

Add analytics and quality metrics:
```java
public class CallQualityMonitor {
    public void trackCallStart(String connectionId);
    public void trackCallEnd(String connectionId, String reason);
    public void trackCallFailure(String connectionId, String error);
    public Map<String, Object> getCallMetrics(String connectionId);
}
```

**Files to Modify:**
- [ ] Create: `CallQualityMonitor.java`
- [ ] Modify: `MyConnectionService.java` - Track metrics
- [ ] Modify: `CallKitVoipPlugin.java` - Expose metrics to JS

#### 3.4 Add Deep Linking Support
**Priority:** 🟢 LOW

**Problem:** Cannot return to app from call

**Solution:**

Add intent to return to app:
```java
Intent appIntent = context.getPackageManager()
    .getLaunchIntentForPackage(context.getPackageName());
appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
PendingIntent contentIntent = PendingIntent.getActivity(context, 0, appIntent, flags);

notificationBuilder.setContentIntent(contentIntent);
```

**Files to Modify:**
- [ ] Modify: `VoipForegroundService.java` - Add content intent

### Phase 4: Testing & Documentation (Week 4)
**Goal:** Ensure quality and maintainability

#### 4.1 Add Unit Tests
**Priority:** 🟡 MEDIUM

**Test Coverage:**
```java
// CallConfigTest.java
- Test CallConfig creation
- Test field validation

// PhoneAccountHelperTest.java  
- Test PhoneAccount status checking
- Test permission handling
- Mock TelecomManager

// CallConnectionManagerTest.java
- Test retry logic
- Test fallback mechanisms
- Mock Android APIs

// AddressUriParserTest.java
- Test various phone number formats
- Test SIP address parsing
- Test invalid inputs
```

**Files to Create:**
- [ ] `CallConfigTest.java`
- [ ] `PhoneAccountHelperTest.java`
- [ ] `CallConnectionManagerTest.java`
- [ ] `AddressUriParserTest.java`

#### 4.2 Add Integration Tests
**Priority:** 🟡 MEDIUM

**Test Scenarios:**
```java
// IncomingCallTest.java
- Test FCM message received
- Test call UI displayed
- Test call answered
- Test call rejected

// PhoneAccountTest.java
- Test PhoneAccount registration
- Test with PhoneAccount enabled
- Test with PhoneAccount disabled
- Test fallback UI

// PermissionTest.java
- Test with all permissions granted
- Test with permissions denied
- Test permission request flow
```

**Files to Create:**
- [ ] `IncomingCallTest.java`
- [ ] `PhoneAccountTest.java`
- [ ] `PermissionTest.java`

#### 4.3 Update Documentation
**Priority:** 🟡 MEDIUM

**Documents to Update:**
- [ ] `ANDROID_SETUP.md` - Add PhoneAccount enablement steps
- [ ] `ANDROID_SETUP.md` - Add troubleshooting section
- [ ] `README.md` - Update Android requirements
- [ ] Create: `ARCHITECTURE.md` - Document design decisions
- [ ] Create: `TESTING.md` - Testing guidelines

#### 4.4 Add Code Comments
**Priority:** 🟢 LOW

**Comment Guidelines:**
- Document why PhoneAccount can be disabled
- Explain fallback strategy
- Note Android version differences
- Add javadoc for public methods

**Files to Update:**
- [ ] All Java files - Add class-level comments
- [ ] All Java files - Add method-level javadoc
- [ ] Complex logic sections - Add inline comments

## Implementation Checklist

### Week 1: Critical Fixes

**Day 1-2: In-App PhoneAccount Permission Alert** ⭐ HIGH IMPACT
- [ ] Create PhoneAccountHelper.java with status check and Settings opener
- [ ] Add checkPhoneAccountStatus() method to CallKitVoipPlugin.java
- [ ] Add openPhoneAccountSettings() method to CallKitVoipPlugin.java
- [ ] Update src/definitions.ts with PhoneAccountStatus interface
- [ ] Create example implementation in app layer (for documentation)
- [ ] Test on device with PhoneAccount disabled → Alert shows
- [ ] Test "Open Settings" button → Correct Settings page opens
- [ ] Test on Android 5.1 → Shows "not supported" message
- [ ] Test on Android 6+ → Full functionality
- [ ] Add app state listener to detect when user returns from Settings
- [ ] Test success toast shows when PhoneAccount is enabled
- [ ] Document usage in ANDROID_SETUP.md

**Day 3-4: Fallback UI**
- [ ] Update MyFirebaseMessagingService with fallback logic
- [ ] Fix VoipForegroundService parameter names
- [ ] Fix VoipForegroundServiceActionReceiver
- [ ] Test notification UI
- [ ] Test Answer/Reject buttons

**Day 5: Permissions**
- [ ] Update AndroidManifest.xml
- [ ] Add POST_NOTIFICATIONS permission request
- [ ] Test on Android 13+
- [ ] Test on Android 14+

### Week 2: Architecture Cleanup

**Day 1: Remove Dead Code**
- [ ] Delete or repurpose VoipBackgroundService
- [ ] Update manifest
- [ ] Remove unused imports

**Day 2-3: Consolidate Logic**
- [ ] Complete PhoneAccountHelper implementation
- [ ] Refactor CallKitVoipPlugin to use helper
- [ ] Refactor MyFirebaseMessagingService to use helper
- [ ] Remove duplicate code

**Day 4-5: Error Handling**
- [ ] Create CallKitError.java
- [ ] Add notifyError() method
- [ ] Update all error paths to use notifyError()
- [ ] Update TypeScript definitions
- [ ] Test error scenarios

### Week 3: Enhancements

**Day 1-2: Connection Recovery**
- [ ] Create CallConnectionManager
- [ ] Implement retry logic
- [ ] Test retry scenarios

**Day 3: State Persistence**
- [ ] Create CallStateManager
- [ ] Implement save/restore
- [ ] Test app restart scenarios

**Day 4-5: Quality & Deep Linking**
- [ ] Create CallQualityMonitor
- [ ] Add deep linking
- [ ] Test end-to-end

### Week 4: Testing & Documentation

**Day 1-2: Unit Tests**
- [ ] Write unit tests
- [ ] Achieve >80% coverage
- [ ] Fix any bugs found

**Day 3: Integration Tests**
- [ ] Write integration tests
- [ ] Test on multiple devices
- [ ] Test on different Android versions

**Day 4-5: Documentation**
- [ ] Update all documentation
- [ ] Add code comments
- [ ] Create architecture diagrams
- [ ] Write troubleshooting guide

## Success Metrics

### Must Have (P0)
- [ ] Incoming call UI displays 100% of time (either native or notification)
- [ ] PhoneAccount disabled case handled gracefully
- [ ] Works on Android 5.1 through 14+
- [ ] All permissions properly requested
- [ ] No crashes on any supported Android version

### Should Have (P1)
- [ ] Clear user guidance when PhoneAccount is disabled
- [ ] Error events sent to JavaScript layer
- [ ] Unit test coverage >80%
- [ ] Updated documentation

### Nice to Have (P2)
- [ ] Connection retry logic
- [ ] Call state persistence
- [ ] Call quality monitoring
- [ ] Deep linking support

## Risk Assessment

### High Risk Items
1. **Breaking changes to existing API**
   - Mitigation: Maintain backward compatibility
   - Add new methods, deprecate old ones

2. **PhoneAccount behavior differs by manufacturer**
   - Mitigation: Test on multiple devices (Samsung, Pixel, OnePlus, Xiaomi)
   - Implement robust fallback

3. **Permission dialogs disruptive to UX**
   - Mitigation: Request at appropriate times
   - Show clear rationale

### Medium Risk Items
1. **Notification UI may not match native UI**
   - Mitigation: Design to be as close as possible
   - Document differences

2. **Retry logic may cause duplicate calls**
   - Mitigation: Use connectionId to detect duplicates
   - Add maximum retry limit

### Low Risk Items
1. **Performance impact of new features**
   - Mitigation: Profile and optimize
   - Use lazy initialization

## Rollback Plan

### If Critical Issues Found

**Phase 1 Rollback:**
- Keep fallback UI changes
- Remove PhoneAccount helper if problematic
- Revert to direct status checks

**Phase 2 Rollback:**
- Restore VoipBackgroundService if needed
- Revert PhoneAccountHelper consolidation
- Keep error handling improvements

**Phase 3 Rollback:**
- Remove retry logic if causes issues
- Remove state persistence if corrupts data
- Keep monitoring if working

**Complete Rollback:**
- Git revert to last stable commit
- Document issues found
- Plan alternative approach

## Post-Refactor Tasks

### Monitoring (Week 5)
- [ ] Monitor crash reports
- [ ] Monitor user feedback
- [ ] Monitor PhoneAccount enable rate
- [ ] Monitor fallback UI usage rate

### Optimization (Week 6)
- [ ] Profile performance
- [ ] Optimize battery usage
- [ ] Reduce memory footprint
- [ ] Improve startup time

### Future Enhancements
- [ ] Support for multiple simultaneous calls
- [ ] Video call support
- [ ] Call recording capability
- [ ] Screen sharing support
- [ ] Bluetooth headset integration

## Questions for Stakeholders

1. **PhoneAccount Strategy:**
   - Should we strongly encourage PhoneAccount enablement?
   - Or treat notification UI as equal alternative?
   
2. **Permissions:**
   - When should we request permissions? (Registration time vs. first call)
   - How aggressive should permission prompts be?

3. **Backward Compatibility:**
   - Can we drop support for Android 5.1-5.1 (API 22)?
   - This would simplify code significantly

4. **UI Consistency:**
   - Should notification UI match native UI exactly?
   - Or have its own design?

5. **Error Reporting:**
   - Should we send error telemetry to backend?
   - What PII considerations exist?

## Conclusion

This refactor plan addresses the root causes of the incoming call UI issues while improving overall code quality and maintainability. The phased approach allows for incremental delivery and testing, reducing risk.

**Estimated Effort:** 4 weeks (1 developer)  
**Priority:** High  
**ROI:** High - fixes critical user-facing issue  
**Risk:** Medium - requires careful testing on multiple Android versions and devices

The most critical changes are in Phase 1, which directly address the UI display issues. Phases 2-4 improve code quality and user experience but are not blockers for basic functionality.

