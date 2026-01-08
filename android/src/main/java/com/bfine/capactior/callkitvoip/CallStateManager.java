package com.bfine.capactior.callkitvoip;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class CallStateManager {
    private static final String TAG = "CallStateManager";
    private static final String PREFS_NAME = "callkit_state";
    private static final String KEY_ACTIVE_CALLS = "active_calls";
    
    public static void saveCallState(Context context, String connectionId, CallConfig config) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String existingCalls = prefs.getString(KEY_ACTIVE_CALLS, "{}");
            
            JSONObject callsJson = new JSONObject(existingCalls);
            JSONObject callData = new JSONObject();
            callData.put("callId", config.callId);
            callData.put("media", config.media);
            callData.put("duration", config.duration);
            callData.put("bookingId", config.bookingId);
            callData.put("host", config.host);
            callData.put("username", config.username);
            callData.put("secret", config.secret);
            callData.put("timestamp", System.currentTimeMillis());
            
            callsJson.put(connectionId, callData);
            
            prefs.edit().putString(KEY_ACTIVE_CALLS, callsJson.toString()).apply();
            Log.d(TAG, "Saved call state for connectionId: " + connectionId);
        } catch (JSONException e) {
            Log.e(TAG, "Error saving call state", e);
        }
    }
    
    public static Map<String, CallConfig> restoreCallStates(Context context) {
        Map<String, CallConfig> callConfigs = new HashMap<>();
        
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String existingCalls = prefs.getString(KEY_ACTIVE_CALLS, "{}");
            
            JSONObject callsJson = new JSONObject(existingCalls);
            java.util.Iterator<String> keys = callsJson.keys();
            
            while (keys.hasNext()) {
                String connectionId = keys.next();
                JSONObject callData = callsJson.getJSONObject(connectionId);
                
                CallConfig config = new CallConfig(
                    callData.getString("callId"),
                    callData.getString("media"),
                    callData.getString("duration"),
                    callData.getInt("bookingId"),
                    callData.getString("host"),
                    callData.getString("username"),
                    callData.getString("secret")
                );
                
                callConfigs.put(connectionId, config);
            }
            
            Log.d(TAG, "Restored " + callConfigs.size() + " call states");
        } catch (JSONException e) {
            Log.e(TAG, "Error restoring call states", e);
        }
        
        return callConfigs;
    }
    
    public static void clearCallState(Context context, String connectionId) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String existingCalls = prefs.getString(KEY_ACTIVE_CALLS, "{}");
            
            JSONObject callsJson = new JSONObject(existingCalls);
            callsJson.remove(connectionId);
            
            prefs.edit().putString(KEY_ACTIVE_CALLS, callsJson.toString()).apply();
            Log.d(TAG, "Cleared call state for connectionId: " + connectionId);
        } catch (JSONException e) {
            Log.e(TAG, "Error clearing call state", e);
        }
    }
    
    public static void clearAllCallStates(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_ACTIVE_CALLS, "{}").apply();
        Log.d(TAG, "Cleared all call states");
    }
}

