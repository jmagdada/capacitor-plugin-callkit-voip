package com.bfine.capactior.callkitvoip;

import android.util.Log;

import java.util.HashMap;
import java.util.Map;

public class CallQualityMonitor {
    private static final String TAG = "CallQualityMonitor";
    private static final Map<String, CallMetrics> metricsMap = new HashMap<>();
    
    public static class CallMetrics {
        public long startTime;
        public long endTime;
        public String endReason;
        public String error;
        public int retryCount;
        
        public long getDuration() {
            if (endTime > 0) {
                return endTime - startTime;
            }
            return System.currentTimeMillis() - startTime;
        }
    }
    
    public static void trackCallStart(String connectionId) {
        CallMetrics metrics = new CallMetrics();
        metrics.startTime = System.currentTimeMillis();
        metrics.retryCount = 0;
        metricsMap.put(connectionId, metrics);
        Log.d(TAG, "Tracking call start for: " + connectionId);
    }
    
    public static void trackCallEnd(String connectionId, String reason) {
        CallMetrics metrics = metricsMap.get(connectionId);
        if (metrics != null) {
            metrics.endTime = System.currentTimeMillis();
            metrics.endReason = reason;
            Log.d(TAG, "Call ended: " + connectionId + ", reason: " + reason + ", duration: " + metrics.getDuration() + "ms");
        }
    }
    
    public static void trackCallFailure(String connectionId, String error) {
        CallMetrics metrics = metricsMap.get(connectionId);
        if (metrics != null) {
            metrics.error = error;
            Log.e(TAG, "Call failure: " + connectionId + ", error: " + error);
        } else {
            Log.e(TAG, "Call failure for unknown connection: " + connectionId + ", error: " + error);
        }
    }
    
    public static void trackRetry(String connectionId) {
        CallMetrics metrics = metricsMap.get(connectionId);
        if (metrics != null) {
            metrics.retryCount++;
            Log.d(TAG, "Call retry: " + connectionId + ", retry count: " + metrics.retryCount);
        }
    }
    
    public static Map<String, Object> getCallMetrics(String connectionId) {
        Map<String, Object> result = new HashMap<>();
        CallMetrics metrics = metricsMap.get(connectionId);
        
        if (metrics != null) {
            result.put("startTime", metrics.startTime);
            result.put("endTime", metrics.endTime);
            result.put("duration", metrics.getDuration());
            result.put("endReason", metrics.endReason);
            result.put("error", metrics.error);
            result.put("retryCount", metrics.retryCount);
        }
        
        return result;
    }
    
    public static void clearMetrics(String connectionId) {
        metricsMap.remove(connectionId);
        Log.d(TAG, "Cleared metrics for: " + connectionId);
    }
}

