# Migration Guide: CallData Fields Removal

## What Changed

The plugin no longer includes `host`, `username`, and `secret` in the CallData structure. All incoming calls now display as **"Call #[bookingId]"**.

---

## For Plugin Users (JavaScript/TypeScript Apps)

### Breaking Changes in Events

All event listeners will receive CallData **without** these fields:
- ❌ `host`
- ❌ `username`
- ❌ `secret`

### Update Your Code

**Before:**
```typescript
CallKitVoip.addListener('callAnswered', (data: CallData) => {
  const sipHost = data.host;           // ❌ Will be undefined
  const username = data.username;      // ❌ Will be undefined
  const password = data.secret;        // ❌ Will be undefined
  
  connectToSipServer(sipHost, username, password);
});
```

**After:**
```typescript
CallKitVoip.addListener('callAnswered', (data: CallData) => {
  const bookingId = data.bookingId;
  const callId = data.callId;
  
  const credentials = await fetchCallCredentials(bookingId);
  connectToSipServer(credentials.host, credentials.username, credentials.password);
});
```

### Recommended Approach

1. **Fetch credentials securely** when call is answered
2. **Use `bookingId` or `callId`** to retrieve connection details from your backend
3. **Store credentials securely** in your app (never in push notifications)

---

## For Backend Developers

### Push Notification Payload Changes

**Stop sending these fields:**
```json
{
  "type": "call",
  "callId": "abc123",
  "bookingId": "12345",
  "media": "audio",
  "host": "sip.example.com",     // ❌ Remove this
  "username": "John Doe",         // ❌ Remove this
  "secret": "password123"         // ❌ Remove this
}
```

**New minimal payload:**
```json
{
  "type": "call",
  "callId": "abc123",
  "bookingId": "12345",
  "media": "audio"
}
```

### Required Fields

- `type`: Must be "call"
- `callId`: Unique call identifier
- `bookingId`: Used for display name (shows as "Call #12345")
- `media`: "audio" or "video" (defaults to "audio" if missing)

### Optional Fields

- `connectionId`: Auto-generated UUID if not provided
- `duration`: Defaults to "0" if not provided

---

## Display Name Behavior

### Native UI
All platforms now show: **"Call #[bookingId]"**

**Examples:**
- bookingId = 12345 → "Call #12345"
- bookingId = 67890 → "Call #67890"

### Fallback
If bookingId is 0 or missing:
- Android: "Incoming Call"
- iOS: "Call #0"

---

## Security Improvements

✅ **SIP credentials no longer in push notifications**
- Reduced risk if notifications are intercepted
- Credentials fetched via secure API only when needed

✅ **Personal information protected**
- Username not exposed in push payload
- Better privacy compliance

✅ **Best practice compliance**
- Sensitive data transmitted over secure channels only
- Push notifications contain minimal identifying information

---

## Testing Your App

### 1. Update Event Handlers
Remove references to `host`, `username`, `secret`

### 2. Implement Credential Fetching
```typescript
async function fetchCallCredentials(bookingId: string) {
  const response = await fetch(`https://api.example.com/calls/${bookingId}/credentials`, {
    headers: { 'Authorization': 'Bearer YOUR_TOKEN' }
  });
  return response.json();
}
```

### 3. Test Call Flow
1. Send push notification with minimal payload
2. Receive incoming call showing "Call #[bookingId]"
3. Answer call
4. App fetches credentials via API
5. App connects to SIP server with fetched credentials

### 4. Verify Events
Check that event data no longer contains removed fields:
```typescript
CallKitVoip.addListener('callAnswered', (data) => {
  console.log(data);
  // Should only show: callId, media, duration, bookingId
  // Should NOT show: host, username, secret
});
```

---

## Version Compatibility

### This Version (Breaking Changes)
- CallData no longer includes `host`, `username`, `secret`
- Display name is "Call #[bookingId]"

### Previous Version
- CallData included all fields
- Display name was `username` value

### Upgrade Path
1. Update your app code to fetch credentials via API
2. Update backend to stop sending removed fields
3. Test thoroughly before deploying to production
4. Update plugin in your app

---

## Rollback Plan

If issues arise, you can:
1. Keep old plugin version temporarily
2. Ensure backend still supports sending all fields
3. Plan migration in phases:
   - Phase 1: Update backend to send both old and new format
   - Phase 2: Update apps to use new format
   - Phase 3: Remove old fields from backend

---

## Support

If you encounter issues:
1. Check that `bookingId` is always sent in push notifications
2. Verify your API endpoint for fetching credentials
3. Test with different bookingId values (including 0)
4. Check console logs for any errors

---

## Example Implementation

### Complete Example
```typescript
import { CallKitVoip, CallData } from 'capacitor-plugin-callkit-voip';

class CallService {
  constructor() {
    this.setupListeners();
  }

  setupListeners() {
    CallKitVoip.addListener('callAnswered', async (data: CallData) => {
      console.log('Call answered:', data);
      
      const credentials = await this.fetchCredentials(data.bookingId);
      
      await this.connectToSip(
        credentials.host,
        credentials.username,
        credentials.password,
        data.callId
      );
    });

    CallKitVoip.addListener('callRejected', (data: CallData) => {
      console.log('Call rejected:', data.bookingId);
    });

    CallKitVoip.addListener('callEnded', (data: CallData) => {
      console.log('Call ended:', data.bookingId);
      this.cleanup();
    });
  }

  async fetchCredentials(bookingId: string) {
    const response = await fetch(
      `https://api.example.com/calls/${bookingId}/credentials`,
      {
        headers: {
          'Authorization': `Bearer ${this.getAuthToken()}`,
          'Content-Type': 'application/json'
        }
      }
    );
    
    if (!response.ok) {
      throw new Error('Failed to fetch call credentials');
    }
    
    return await response.json();
  }

  async connectToSip(host: string, username: string, password: string, callId: string) {
    console.log(`Connecting to ${host} as ${username}`);
  }

  cleanup() {
  }

  getAuthToken(): string {
    return 'your-auth-token';
  }
}

export default new CallService();
```

---

End of Migration Guide

