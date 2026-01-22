package com.bfine.capactior.callkitvoip;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.bfine.capactior.callkitvoip.androidcall.VoipForegroundService;
import com.getcapacitor.Bridge;

@RequiresApi(api = Build.VERSION_CODES.M)
public class MyConnectionService extends ConnectionService {

    private static final String TAG = "MyConnectionService";
    private static Connection currentConnection;

    public static Connection getConnection() {
        return currentConnection;
    }

    public static void deinitConnection() {
        currentConnection = null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "MyConnectionService onCreate called");
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public Connection onCreateIncomingConnection(
            final PhoneAccountHandle connectionManagerPhoneAccount, 
            final ConnectionRequest request) {
        
        Log.d(TAG, "onCreateIncomingConnection called");
        if (connectionManagerPhoneAccount != null) {
            Log.d(TAG, "PhoneAccountHandle: " + connectionManagerPhoneAccount.toString());
        }
        if (request != null) {
            if (request.getExtras() != null) {
                Log.d(TAG, "Request extras: " + request.getExtras().toString());
            }
            if (request.getAddress() != null) {
                Log.d(TAG, "Request address: " + request.getAddress().toString());
            }
        } else {
            Log.e(TAG, "ConnectionRequest is null!");
            return null;
        }
        
        if (currentConnection != null) {
            Log.w(TAG, "There is already an active connection. Cleaning up...");
            try {
                currentConnection.setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
                currentConnection.destroy();
            } catch (Exception e) {
                Log.e(TAG, "Error cleaning up existing connection", e);
            }
            currentConnection = null;
        }
        
        try {
            final Connection connection = new Connection() {
                @Override
                public void onShowIncomingCallUi() {
                    super.onShowIncomingCallUi();
                    String connectionId = request.getExtras().getString("connectionId");
                    String displayName = request.getExtras().getString("displayName");
                    
                    Log.d(TAG, "onShowIncomingCallUi called - showing notification UI, connectionId: " + connectionId);
                    
                    try {
                        Intent serviceIntent = new Intent(MyConnectionService.this, VoipForegroundService.class);
                        serviceIntent.setAction("incoming");
                        serviceIntent.putExtra("connectionId", connectionId);
                        serviceIntent.putExtra("displayName", displayName != null ? displayName : "Incoming Call");
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent);
                        } else {
                            startService(serviceIntent);
                        }
                        
                        Log.d(TAG, "Notification service started successfully");
                    } catch (Exception e) {
                        Log.e(TAG, "Error showing incoming call UI", e);
                    }
                }

                @RequiresApi(api = Build.VERSION_CODES.O)
                @Override
                public void onAnswer() {
                    this.setActive();
                    
                    String connectionId = request.getExtras().getString("connectionId");
                    
                    Log.d(TAG, "Call answered - connectionId: " + connectionId);
                    
                    CallQualityMonitor.trackCallEnd(connectionId, "User answered");
                    
                    CallKitVoipPlugin plugin = CallKitVoipPlugin.getInstance();
                    if (plugin != null) {
                        plugin.notifyEvent("callAnswered", connectionId);
                    }
                }

                @Override
                public void onReject() {
                    String connectionId = request.getExtras().getString("connectionId");
                    
                    Log.d(TAG, "Call rejected - connectionId: " + connectionId);
                    
                    CallQualityMonitor.trackCallEnd(connectionId, "User rejected");
                    
                    CallKitVoipPlugin plugin = CallKitVoipPlugin.getInstance();
                    if (plugin != null) {
                        plugin.notifyEvent("callRejected", connectionId);
                    }
                    
                    CallKitVoipPlugin.removeCallConfig(connectionId);
                    CallStateManager.clearCallState(getApplicationContext(), connectionId);
                    CallQualityMonitor.clearMetrics(connectionId);
                    
                    DisconnectCause cause = new DisconnectCause(DisconnectCause.REJECTED);
                    this.setDisconnected(cause);
                    this.destroy();
                    currentConnection = null;
                }

                @Override
                public void onAbort() {
                    super.onAbort();
                    currentConnection = null;
                }

                @Override
                public void onDisconnect() {
                    String connectionId = request.getExtras().getString("connectionId");
                    
                    Log.d(TAG, "Call disconnected - connectionId: " + connectionId);
                    
                    CallQualityMonitor.trackCallEnd(connectionId, "User disconnected");
                    
                    CallKitVoipPlugin plugin = CallKitVoipPlugin.getInstance();
                    if (plugin != null) {
                        plugin.notifyEvent("callEnded", connectionId);
                    }
                    
                    CallKitVoipPlugin.removeCallConfig(connectionId);
                    CallStateManager.clearCallState(getApplicationContext(), connectionId);
                    CallQualityMonitor.clearMetrics(connectionId);
                    
                    DisconnectCause cause = new DisconnectCause(DisconnectCause.LOCAL);
                    this.setDisconnected(cause);
                    this.destroy();
                    currentConnection = null;
                }
            };
            
            Bundle extras = request.getExtras();
            if (extras == null) {
                Log.e(TAG, "Request extras is null");
                return null;
            }
            
            String connectionId = extras.getString("connectionId");
            String displayName = extras.getString("displayName");
            
            if (displayName == null || displayName.isEmpty()) {
                displayName = "Incoming Call";
            }
            
            Uri addressUri = Uri.fromParts("sip", connectionId != null ? connectionId : "unknown", null);
            
            connection.setAddress(addressUri, TelecomManager.PRESENTATION_ALLOWED);
            Log.d(TAG, "Set address: " + addressUri);
            
            connection.setCallerDisplayName(displayName, TelecomManager.PRESENTATION_ALLOWED);
            Log.d(TAG, "Set display name: " + displayName);
            
            connection.setAudioModeIsVoip(true);
            Log.d(TAG, "Set audio mode to VoIP");
            
            int capabilities = Connection.CAPABILITY_HOLD | 
                               Connection.CAPABILITY_MUTE;
            connection.setConnectionCapabilities(capabilities);
            Log.d(TAG, "Set connection capabilities: " + capabilities);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                connection.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED);
                Log.d(TAG, "Set connection property: SELF_MANAGED");
            }
            connection.setVideoState(VideoProfile.STATE_AUDIO_ONLY);
            Log.d(TAG, "Set video state: AUDIO_ONLY");
            
            currentConnection = connection;
            Log.d(TAG, "Connection stored, system will handle INITIALIZING -> NEW transitions");
            
            Handler handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        int currentState = connection.getState();
                        Log.d(TAG, "Current connection state before setRinging: " + currentState);
                        
                        if (currentState == Connection.STATE_NEW || currentState == Connection.STATE_INITIALIZING) {
                            connection.setRinging();
                            Log.d(TAG, "Set connection to RINGING state");
                            
                            int state = connection.getState();
                            Log.d(TAG, "Connection state after setRinging: " + state + " (2=RINGING)");
                        } else {
                            Log.w(TAG, "Connection state is " + currentState + ", not setting to RINGING");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error setting connection to RINGING state", e);
                    }
                }
            }, 100);
            
            Log.d(TAG, "Connection created successfully with address: " + addressUri + ", displayName: " + displayName);
            
            Log.d(TAG, "Connection created successfully with address: " + addressUri + ", displayName: " + displayName);
            return connection;
            
        } catch (Exception e) {
            Log.e(TAG, "Error creating incoming connection", e);
            return null;
        }
    }
    
    @Override
    public void onCreateIncomingConnectionFailed(PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        Log.e(TAG, "onCreateIncomingConnectionFailed called - Connection creation was rejected by system");
        
        String connectionId = null;
        if (request != null && request.getExtras() != null) {
            connectionId = request.getExtras().getString("connectionId");
            if (connectionId != null) {
                CallQualityMonitor.trackCallFailure(connectionId, "Connection creation failed - PhoneAccount may be disabled");
            }
        }
        
        if (connectionManagerPhoneAccount != null) {
            Log.e(TAG, "PhoneAccountHandle: " + connectionManagerPhoneAccount.toString());
        } else {
            Log.e(TAG, "PhoneAccountHandle is null - this indicates the PhoneAccount may have been disabled");
        }
        if (request != null) {
            if (request.getExtras() != null) {
                Log.e(TAG, "Failed connection extras: " + request.getExtras().toString());
            } else {
                Log.e(TAG, "Request extras is null");
            }
            if (request.getAddress() != null) {
                Log.e(TAG, "Request address: " + request.getAddress());
            }
        } else {
            Log.e(TAG, "ConnectionRequest is null");
        }
        
        if (currentConnection != null) {
            Log.w(TAG, "Cleaning up existing connection due to failure");
            try {
                currentConnection.setDisconnected(new DisconnectCause(DisconnectCause.ERROR));
                currentConnection.destroy();
            } catch (Exception e) {
                Log.e(TAG, "Error cleaning up connection", e);
            }
            currentConnection = null;
        }
        
        Log.e(TAG, "This usually means the PhoneAccount is not enabled. Please check Settings > Calls > Calling accounts and enable the VoIP Account.");
        
        CallKitVoipPlugin plugin = CallKitVoipPlugin.getInstance();
        if (plugin != null && connectionId != null) {
            plugin.notifyError(CallKitError.CONNECTION_FAILED, 
                "Failed to create incoming connection. PhoneAccount may be disabled.");
        }
        
        super.onCreateIncomingConnectionFailed(connectionManagerPhoneAccount, request);
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public Connection onCreateOutgoingConnection(
            PhoneAccountHandle connectionManagerPhoneAccount, 
            ConnectionRequest request) {
        return super.onCreateOutgoingConnection(connectionManagerPhoneAccount, request);
    }
}
