package com.bfine.capactior.callkitvoip;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class CallConnectionManager {
    private static final String TAG = "CallConnectionManager";
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000;
    
    public interface RetryCallback {
        void onRetry(int retryCount);
        void onSuccess();
        void onFailure(Exception lastError);
    }
    
    public static void executeWithRetry(Runnable operation, RetryCallback callback) {
        executeWithRetry(operation, callback, 0, null);
    }
    
    private static void executeWithRetry(Runnable operation, RetryCallback callback, int retryCount, Exception lastError) {
        if (retryCount >= MAX_RETRIES) {
            Log.e(TAG, "Max retries reached, giving up");
            if (callback != null) {
                callback.onFailure(lastError);
            }
            return;
        }
        
        try {
            operation.run();
            if (callback != null) {
                callback.onSuccess();
            }
        } catch (Exception e) {
            Log.w(TAG, "Operation failed, retry attempt " + (retryCount + 1) + "/" + MAX_RETRIES, e);
            
            if (callback != null) {
                callback.onRetry(retryCount + 1);
            }
            
            long delay = RETRY_DELAY_MS * (long) Math.pow(2, retryCount);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                executeWithRetry(operation, callback, retryCount + 1, e);
            }, delay);
        }
    }
}

