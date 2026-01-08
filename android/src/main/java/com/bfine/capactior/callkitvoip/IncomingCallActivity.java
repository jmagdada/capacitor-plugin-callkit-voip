package com.bfine.capactior.callkitvoip;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.telecom.Connection;
import android.telecom.DisconnectCause;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

public class IncomingCallActivity extends Activity {
    private static final String TAG = "IncomingCallActivity";
    private PowerManager.WakeLock wakeLock;
    private String connectionId;
    private String username;
    private String from;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Log.d(TAG, "IncomingCallActivity onCreate");
        
        setupWindow();
        setupWakeLock();
        
        setContentView(getResources().getIdentifier("activity_incoming_call", "layout", getPackageName()));
        
        connectionId = getIntent().getStringExtra("connectionId");
        username = getIntent().getStringExtra("username");
        from = getIntent().getStringExtra("from");
        
        if (username == null || username.isEmpty()) {
            username = "Unknown Caller";
        }
        if (from == null || from.isEmpty()) {
            from = username;
        }
        
        Log.d(TAG, "Incoming call from: " + username + " (" + from + "), connectionId: " + connectionId);
        
        setupUI();
    }
    
    private void setupWindow() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        
        Window window = getWindow();
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        );
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (keyguardManager != null) {
                keyguardManager.requestDismissKeyguard(this, null);
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        } else {
            window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            );
        }
    }
    
    private void setupWakeLock() {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | 
                PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "CallKitVoip:IncomingCallActivityWakeLock"
            );
            wakeLock.acquire(60000);
            Log.d(TAG, "WakeLock acquired for IncomingCallActivity");
        }
    }
    
    private void setupUI() {
        TextView callerNameView = findViewById(getResources().getIdentifier("caller_name", "id", getPackageName()));
        TextView callerNumberView = findViewById(getResources().getIdentifier("caller_number", "id", getPackageName()));
        View answerButton = findViewById(getResources().getIdentifier("answer_button", "id", getPackageName()));
        View rejectButton = findViewById(getResources().getIdentifier("reject_button", "id", getPackageName()));
        
        if (callerNameView != null) {
            callerNameView.setText(username);
        }
        
        if (callerNumberView != null) {
            if (!from.equals(username) && !from.isEmpty()) {
                callerNumberView.setText(from);
                callerNumberView.setVisibility(View.VISIBLE);
            } else {
                callerNumberView.setVisibility(View.GONE);
            }
        }
        
        if (answerButton != null) {
            answerButton.setOnClickListener(v -> answerCall());
        }
        
        if (rejectButton != null) {
            rejectButton.setOnClickListener(v -> rejectCall());
        }
    }
    
    private void answerCall() {
        Log.d(TAG, "Answer button clicked for connectionId: " + connectionId);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Connection connection = MyConnectionService.getConnection();
            if (connection != null) {
                connection.setActive();
                Log.d(TAG, "Connection set to ACTIVE via incoming call activity");
            } else {
                Log.w(TAG, "Connection is null, cannot set active");
            }
        }
        
        CallKitVoipPlugin instance = CallKitVoipPlugin.getInstance();
        if (instance != null) {
            instance.notifyEvent("callAnswered", connectionId);
        }
        
        launchMainApp();
        finish();
    }
    
    private void rejectCall() {
        Log.d(TAG, "Reject button clicked for connectionId: " + connectionId);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Connection connection = MyConnectionService.getConnection();
            if (connection != null) {
                DisconnectCause cause = new DisconnectCause(DisconnectCause.REJECTED);
                connection.setDisconnected(cause);
                connection.destroy();
                MyConnectionService.deinitConnection();
                Log.d(TAG, "Connection rejected and destroyed via incoming call activity");
            }
        }
        
        CallKitVoipPlugin instance = CallKitVoipPlugin.getInstance();
        if (instance != null) {
            instance.notifyEvent("callRejected", connectionId);
        }
        
        CallKitVoipPlugin.removeCallConfig(connectionId);
        CallStateManager.clearCallState(this, connectionId);
        CallQualityMonitor.clearMetrics(connectionId);
        
        finish();
    }
    
    private void launchMainApp() {
        try {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | 
                                     Intent.FLAG_ACTIVITY_CLEAR_TOP | 
                                     Intent.FLAG_ACTIVITY_SINGLE_TOP);
                launchIntent.putExtra("connectionId", connectionId);
                launchIntent.putExtra("username", username);
                launchIntent.putExtra("isIncomingCall", true);
                launchIntent.putExtra("callAnswered", true);
                startActivity(launchIntent);
                Log.d(TAG, "Launched main app");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error launching main app", e);
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "WakeLock released");
        }
    }
    
    @Override
    public void onBackPressed() {
    }
}

