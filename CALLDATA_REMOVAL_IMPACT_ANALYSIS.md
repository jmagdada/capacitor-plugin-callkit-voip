# CallData Fields Removal Impact Analysis

## Summary
Analysis of removing `host`, `username`, and `secret` fields from CallData structure.

---

## Fields to Remove
- `host`: String - SIP/VoIP host address
- `username`: String - Caller username/display name
- `secret`: String - SIP/VoIP password/secret

---

## TypeScript/Definitions

### File: `src/definitions.ts`
**Lines Affected:** 85-93

```typescript
export interface CallData {
  callId:string;
  media?: CallType;
  duration?:string;
  bookingId?:string;
  host?:string;        // TO REMOVE
  username?:string;    // TO REMOVE
  secret?:string;      // TO REMOVE
}
```

**Impact:** LOW
- These are optional fields marked with `?`
- Simply remove the three property definitions
- No breaking changes to TypeScript consumers if they don't explicitly use these fields

---

## Android Implementation

### 1. CallConfig.java
**Location:** `android/src/main/java/com/bfine/capactior/callkitvoip/CallConfig.java`
**Lines Affected:** 8-10, 13, 18-20

**Current Structure:**
```java
public class CallConfig {
    public final String callId;
    public final String media;
    public final String duration;
    public final int bookingId;
    public final String host;        // TO REMOVE
    public final String username;    // TO REMOVE
    public final String secret;      // TO REMOVE
    
    public CallConfig(String callId, String media, String duration, int bookingId, 
                     String host, String username, String secret) { // TO UPDATE
        this.host = host;           // TO REMOVE
        this.username = username;   // TO REMOVE
        this.secret = secret;       // TO REMOVE
    }
}
```

**Impact:** HIGH
- **Core data structure** - all other classes depend on this
- Must update constructor signature (remove 3 parameters)
- All instantiations of CallConfig must be updated

---

### 2. MyFirebaseMessagingService.java
**Location:** `android/src/main/java/com/bfine/capactior/callkitvoip/MyFirebaseMessagingService.java`
**Lines Affected:** 45-47, 76-93, 102, 110, 113, 124, 130, 138, 142-145, 155-156, 165, 173, 175, 184, 188, 205-206, 214

**Current Usage:**
```java
String host = remoteMessage.getData().get("host");           // line 45
String username = remoteMessage.getData().get("username");   // line 46
String secret = remoteMessage.getData().get("secret");       // line 47

if (host == null) { host = ""; }                            // lines 76-78
if (username == null) { username = "Unknown"; }             // lines 79-81
if (secret == null) { secret = ""; }                        // lines 82-84

CallConfig config = new CallConfig(
    callId, media, duration, bookingId,
    host, username, secret                                   // lines 91-93
);

showNativeIncomingCall(connectionId, username, fromNumber);  // line 102, 110, 113
showNotificationIncomingCall(connectionId, username, fromNumber); // line 110, 113, 130, 138, 175, 184
```

**Impact:** HIGH
- Remove extraction of `host`, `username`, `secret` from push notification data
- **CRITICAL:** `username` is passed to UI methods - need alternative display name
- Update `CallConfig` instantiation
- Update method signatures for `showNativeIncomingCall()` and `showNotificationIncomingCall()`
- **Alternative:** Use `fromNumber` or derive display name from `bookingId` or `callId`

---

### 3. CallKitVoipPlugin.java
**Location:** `android/src/main/java/com/bfine/capactior/callkitvoip/CallKitVoipPlugin.java`
**Lines Affected:** 384, 391-393

**Current Usage:**
```java
public void notifyEvent(String eventName, String connectionId) {
    CallConfig config = connectionIdRegistry.get(connectionId);
    Log.d("notifyEvent", eventName + "  " + config.username + "   " + connectionId); // line 384
    
    JSObject data = new JSObject();
    data.put("callId", config.callId);
    data.put("media", config.media);
    data.put("duration", config.duration);
    data.put("bookingId", config.bookingId);
    data.put("host", config.host);           // line 391 - TO REMOVE
    data.put("username", config.username);   // line 392 - TO REMOVE
    data.put("secret", config.secret);       // line 393 - TO REMOVE
    notifyListeners(eventName, data);
}
```

**Impact:** MEDIUM
- Remove three `data.put()` calls
- Update logging line (remove username reference)
- **JavaScript Impact:** Events sent to JS will no longer contain these fields
- Apps relying on these fields in event listeners will break

---

### 4. CallStateManager.java
**Location:** `android/src/main/java/com/bfine/capactior/callkitvoip/CallStateManager.java`
**Lines Affected:** 29-31, 62-64

**Current Usage:**
```java
public static void saveCallState(Context context, String connectionId, CallConfig config) {
    JSONObject callData = new JSONObject();
    callData.put("callId", config.callId);
    callData.put("media", config.media);
    callData.put("duration", config.duration);
    callData.put("bookingId", config.bookingId);
    callData.put("host", config.host);           // line 29 - TO REMOVE
    callData.put("username", config.username);   // line 30 - TO REMOVE
    callData.put("secret", config.secret);       // line 31 - TO REMOVE
}

public static Map<String, CallConfig> restoreCallStates(Context context) {
    CallConfig config = new CallConfig(
        callData.getString("callId"),
        callData.getString("media"),
        callData.getString("duration"),
        callData.getInt("bookingId"),
        callData.getString("host"),          // line 62 - TO REMOVE
        callData.getString("username"),      // line 63 - TO REMOVE
        callData.getString("secret")         // line 64 - TO REMOVE
    );
}
```

**Impact:** MEDIUM
- Remove JSON serialization/deserialization of three fields
- Update `CallConfig` constructor call in restore method
- **Migration:** Existing saved states in SharedPreferences will have these fields but won't cause errors

---

### 5. MyConnectionService.java
**Location:** `android/src/main/java/com/bfine/capactior/callkitvoip/MyConnectionService.java`
**Lines Affected:** 82-83, 91-92, 183, 185, 200

**Current Usage:**
```java
public void onShowIncomingCallUi() {
    String username = request.getExtras().getString("username");  // line 82
    serviceIntent.putExtra("username", username != null ? username : "Unknown Caller"); // line 91
}

Bundle extras = request.getExtras();
String username = extras.getString("username");                   // line 183
String addressString = username != null ? username : (fromNumber != null ? fromNumber : "unknown"); // line 185
String displayName = fromNumber != null ? fromNumber : (username != null ? username : "Unknown"); // line 200
```

**Impact:** MEDIUM
- `username` used for display name in CallKit UI
- Need to pass alternative display name through extras
- **Solution:** Use `fromNumber` or generate from `bookingId`

---

### 6. VoipForegroundService.java
**Location:** `android/src/main/java/com/bfine/capactior/callkitvoip/androidcall/VoipForegroundService.java`
**Lines Affected:** 36, 143, 149-150, 156, 165, 173, 202-204

**Current Usage:**
```java
String username="", connectionId="", from="";                    // line 36

username = intent.getStringExtra("username");                    // line 143
if (username == null || username.isEmpty()) {
    username = "Unknown Caller";                                 // line 150
}

serviceIntent.putExtra("username", username);                    // line 165
cancelCallAction.putExtra("username", username);                 // line 173

String displayName = username;
if (!from.equals(username) && !from.isEmpty()) {
    displayName = username + " (" + from + ")";                  // lines 202-204
}
```

**Impact:** HIGH
- `username` heavily used for notification display
- Used in notification title, logs, and intent extras
- **Solution:** Replace with `from` field or generate display name from available data

---

### 7. VoipForegroundServiceActionReceiver.java
**Location:** `android/src/main/java/com/bfine/capactior/callkitvoip/androidcall/VoipForegroundServiceActionReceiver.java`
**Lines Affected:** 22, 26, 31-32, 50, 54-55 (from grep results)

**Current Usage:**
```java
String username = intent.getStringExtra("username");
performClickAction(context, action, username, connectionId);
private void performClickAction(Context context, String action, String username, String connectionId)
public void endCall(String username, String connectionId)
```

**Impact:** MEDIUM
- `username` passed to methods but primarily used for logging
- Can be removed or replaced with alternative identifier

---

## iOS Implementation

### File: ios/Plugin/CallKitVoipPlugin.swift
**Lines Affected:** 40-42, 53-55, 64, 72-74, 160-162, 169-171, 189-191

**Current Structure:**
```swift
struct CallConfig {
    let callId: String
    let media: String
    let duration: String
    let bookingId: Int
    let host: String        // line 189 - TO REMOVE
    let username: String    // line 190 - TO REMOVE
    let secret: String      // line 191 - TO REMOVE
}

public func notifyEvent(eventName: String, uuid: UUID){
    notifyListeners(eventName, data: [
        "callId": config.callId,
        "media": config.media,
        "duration": config.duration,
        "bookingId": config.bookingId,
        "username": config.username,  // line 40 - TO REMOVE
        "secret": config.secret,      // line 41 - TO REMOVE
        "host": config.host,          // line 42 - TO REMOVE
    ])
}

public func incomingCall(
    callId: String,
    media: String,
    duration: String,
    bookingId: Int,
    host: String,             // line 53 - TO REMOVE
    username: String,         // line 54 - TO REMOVE
    secret: String            // line 55 - TO REMOVE
) {
    update.localizedCallerName = "\(username)"  // line 64 - NEEDS ALTERNATIVE
    
    connectionIdRegistry[uuid] = .init(
        callId: callId,
        media: media,
        duration: duration,
        bookingId: bookingId,
        host: host,           // line 72 - TO REMOVE
        username: username,   // line 73 - TO REMOVE
        secret: secret        // line 74 - TO REMOVE
    )
}

let host = (aData["host"] as? String) ?? ""              // line 160
let username = (aData["username"] as? String) ?? "Unknown"  // line 161
let secret = (aData["secret"] as? String) ?? ""          // line 162

self.incomingCall(
    callId: callId,
    media: media,
    duration: duration,
    bookingId: bookingId,
    host: host,           // line 169
    username: username,   // line 170
    secret: secret        // line 171
)
```

**Impact:** HIGH
- Remove three fields from `CallConfig` struct
- Update `incomingCall()` method signature
- **CRITICAL:** `username` used for `localizedCallerName` in CallKit UI
- Remove extraction from push payload
- Remove from event notification data
- **Alternative:** Use `bookingId` or other field for display name

---

## Impact Summary by Field

### `host` Field
**Overall Impact: LOW**
- **Purpose:** SIP/VoIP server hostname
- **Usage:** Only stored and passed through to JavaScript
- **Never used** in native Android/iOS UI or logic
- **Safe to remove** - no functional impact on native code
- **JavaScript Impact:** Apps expecting this field for SIP connection will need alternative

### `username` Field
**Overall Impact: HIGH**
- **Purpose:** Display name for caller
- **Usage:** Extensively used in UI
  - Android: Notification titles, CallKit display name, logging
  - iOS: CallKit `localizedCallerName`
- **Cannot simply remove** - need replacement strategy
- **Alternative Solutions:**
  1. Use `from` field (already exists in Android push notification)
  2. Use `bookingId` formatted as string
  3. Use `callId`
  4. Add new `displayName` or `callerName` field
  5. Use combination: "Call #[bookingId]"

### `secret` Field
**Overall Impact: LOW**
- **Purpose:** SIP/VoIP authentication password
- **Usage:** Only stored and passed through to JavaScript
- **Never used** in native Android/iOS UI or logic
- **Security concern:** Sensitive credential should not be in push notification
- **Safe to remove** - no functional impact on native code
- **JavaScript Impact:** Apps expecting this field for SIP authentication will need alternative

---

## Recommended Approach

### Option 1: Remove `host` and `secret`, Keep `username`
**Rationale:** 
- `host` and `secret` are not used natively and can be obtained via API call when needed
- `username` provides better UX for caller identification
- Minimal code changes

**Changes Required:**
- TypeScript: Remove `host?` and `secret?` from `CallData` interface
- Android: Remove 2 fields from `CallConfig`, update all instantiations
- iOS: Remove 2 fields from `CallConfig` struct, update methods
- Keep `username` field as-is

### Option 2: Remove All Three, Use `from` Field
**Rationale:**
- Android push notification already includes `from` field (line 48 in MyFirebaseMessagingService)
- Can use `from` as display name replacement
- Complete removal of connection credentials from push notifications (security benefit)

**Changes Required:**
1. TypeScript: Remove all three fields from `CallData` interface
2. Android:
   - Remove three fields from `CallConfig`
   - Replace `username` with `from` in all display logic
   - Add `from` to `CallConfig` constructor
   - Update all UI to use `from` instead of `username`
3. iOS:
   - Remove three fields from `CallConfig`
   - Add `from` to push payload extraction
   - Use `from` for `localizedCallerName`
   - Update `incomingCall()` method signature

### Option 3: Remove All Three, Use `bookingId` for Display
**Rationale:**
- Display as "Call #12345" format
- No personal information in display name
- Consistent across platforms

**Changes Required:**
1. TypeScript: Remove all three fields
2. Android:
   - Remove three fields from `CallConfig`
   - Format display name as: `"Call #" + config.bookingId`
   - Update all UI locations
3. iOS:
   - Remove three fields from `CallConfig`
   - Format display name as: `"Call #\(bookingId)"`
   - Update CallKit configuration

---

## Files Summary

### Must Edit (Core Structure)
1. `src/definitions.ts` - Interface definition
2. `android/src/main/java/com/bfine/capactior/callkitvoip/CallConfig.java` - Core data class
3. `ios/Plugin/CallKitVoipPlugin.swift` - iOS implementation

### Must Update (Usage)
4. `android/src/main/java/com/bfine/capactior/callkitvoip/MyFirebaseMessagingService.java`
5. `android/src/main/java/com/bfine/capactior/callkitvoip/CallKitVoipPlugin.java`
6. `android/src/main/java/com/bfine/capactior/callkitvoip/CallStateManager.java`
7. `android/src/main/java/com/bfine/capactior/callkitvoip/MyConnectionService.java`
8. `android/src/main/java/com/bfine/capactior/callkitvoip/androidcall/VoipForegroundService.java`
9. `android/src/main/java/com/bfine/capactior/callkitvoip/androidcall/VoipForegroundServiceActionReceiver.java`

### Total Files to Modify: 10 files

---

## Breaking Changes for JavaScript Consumers

### Event Listeners Affected
All event listeners will no longer receive these fields:
- `callAnswered` event
- `callStarted` event
- `callEnded` event
- `callRejected` event

**Example Before:**
```javascript
CallKitVoip.addListener('callAnswered', (data) => {
  console.log(data.host);      // Will be undefined
  console.log(data.username);  // Will be undefined
  console.log(data.secret);    // Will be undefined
});
```

**Apps must:**
1. Remove references to these fields from event handlers
2. Fetch connection details via API using `callId` or `bookingId`
3. Store credentials securely in app, not in push notifications

---

## Security Benefits

Removing `host`, `username`, and `secret` from push notifications:
1. **Reduces credential exposure** - sensitive SIP credentials not in push payload
2. **Follows security best practices** - authentication credentials should be fetched via secure API
3. **Reduces payload size** - smaller push notifications
4. **Prevents credential interception** - if push notification is intercepted, no credentials exposed

---

## Migration Strategy

1. **Phase 1:** Update backend to stop sending `host`, `username`, `secret` in push notifications
2. **Phase 2:** Update plugin to remove fields
3. **Phase 3:** Update consumer apps to fetch credentials via API when call is answered
4. **Phase 4:** Release new plugin version with breaking change notice

---

## Questions to Answer

1. **What should be used as display name?**
   - `from` field?
   - `bookingId` formatted?
   - New field in payload?

2. **How will JavaScript layer get connection credentials?**
   - API call when call is answered?
   - Stored locally?
   - Embedded in app?

3. **Is `from` field always available in push notification?**
   - Currently used as fallback in Android (line 48-52 in MyFirebaseMessagingService)
   - Should iOS also receive `from` field?

4. **Version bump?**
   - Major version (breaking change)
   - Document migration guide

---

## Testing Required After Changes

1. **Android:**
   - Test incoming call with native CallKit UI
   - Test incoming call with notification fallback
   - Test call answer, reject, hangup
   - Test call restoration after app restart
   - Verify display name shows correctly

2. **iOS:**
   - Test incoming call with CallKit UI
   - Test display name in CallKit screen
   - Test call answer, reject, hangup
   - Verify all events fire correctly

3. **JavaScript:**
   - Verify events no longer contain removed fields
   - Test that app handles missing fields gracefully
   - Verify alternative credential fetching works

---

End of Analysis

