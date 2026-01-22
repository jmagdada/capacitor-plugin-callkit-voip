package com.bfine.capactior.callkitvoip.androidcall;

import android.app.KeyguardManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Vibrator;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;


import java.util.Objects;

public class VoipForegroundService extends Service {
    private String INCOMING_CHANNEL_ID = "IncomingCallChannel";
    private String INCOMING_CHANNEL_NAME = "Incoming Call Channel";
    private String ONGOING_CHANNEL_ID = "OngoingCallChannel";
    private String ONGOING_CHANNEL_NAME = "Ongoing Call Channel";
    private NotificationManager mNotificationManager;
    NotificationCompat.Builder notificationBuilder;
    public static MediaPlayer ringtone;
    public static Vibrator vibrator;
    String displayName="", connectionId="";
    private PowerManager.WakeLock wakeLock;
    private KeyguardManager.KeyguardLock keyguardLock;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stop_ringtone();
        releaseWakeLock();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        acquireWakeLock();
    }
    
    private void acquireWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | 
                    PowerManager.ACQUIRE_CAUSES_WAKEUP | 
                    PowerManager.ON_AFTER_RELEASE,
                    "CallKitVoip:IncomingCallWakeLock"
                );
                wakeLock.acquire(60000);
                Log.d("VoipForegroundService", "WakeLock acquired");
            }
        } catch (Exception e) {
            Log.e("VoipForegroundService", "Error acquiring wake lock", e);
        }
    }
    
    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                Log.d("VoipForegroundService", "WakeLock released");
            }
        } catch (Exception e) {
            Log.e("VoipForegroundService", "Error releasing wake lock", e);
        }
    }
    
    private void turnScreenOnAndKeyguardOff() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                boolean isScreenOn = powerManager.isInteractive();
                Log.d("VoipForegroundService", "Screen interactive: " + isScreenOn);
                
                if (!isScreenOn) {
                    PowerManager.WakeLock screenLock = powerManager.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK | 
                        PowerManager.ACQUIRE_CAUSES_WAKEUP |
                        PowerManager.ON_AFTER_RELEASE,
                        "CallKitVoip:ScreenWakeLock"
                    );
                    screenLock.acquire(5000);
                    Log.d("VoipForegroundService", "Screen wake lock acquired to turn on screen");
                    
                    new android.os.Handler().postDelayed(() -> {
                        if (screenLock.isHeld()) {
                            screenLock.release();
                            Log.d("VoipForegroundService", "Screen wake lock released");
                        }
                    }, 5000);
                }
            }
        } catch (Exception e) {
            Log.e("VoipForegroundService", "Error turning screen on", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();
        Log.d("VoipForegroundService","onStartCommand "+action);
        switch (action)
        {
            case "incoming":
                build_incoming_call_notification(intent);
                break;
            case "answered":
                build_answered_call_notification();
                break;

        }
        return START_NOT_STICKY;
    }



    public void build_incoming_call_notification(Intent intent)
    {
        turnScreenOnAndKeyguardOff();
        
        ringtone = new MediaPlayer();
        connectionId = intent.getStringExtra("connectionId");
        displayName = intent.getStringExtra("displayName");
        
        if (connectionId == null || connectionId.isEmpty()) {
            connectionId = "";
        }
        if (displayName == null || displayName.isEmpty()) {
            displayName = "Incoming Call";
        }
        
        Log.d("VoipForegroundService","build_incoming_call_notification for "+displayName+" (connectionId: "+connectionId+")");

        try {
            Intent answerCallIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (answerCallIntent != null) {
                answerCallIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | 
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP | 
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP);
                answerCallIntent.putExtra("connectionId", connectionId);
                answerCallIntent.putExtra("displayName", displayName);
                answerCallIntent.putExtra("isIncomingCall", true);
                answerCallIntent.putExtra("callAnswered", true);
            }

            Intent cancelCallAction = new Intent(getApplicationContext(), VoipForegroundServiceActionReceiver.class);
            cancelCallAction.putExtra("connectionId", connectionId);
            cancelCallAction.putExtra("displayName", displayName);
            cancelCallAction.setAction("CANCEL_CALL");

            int immutableFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                immutableFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            
            int mutableFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mutableFlags |= PendingIntent.FLAG_MUTABLE;
            }

            PendingIntent receiveCallPendingIntent = null;
            PendingIntent cancelCallPendingIntent = PendingIntent.getBroadcast(getApplicationContext(), 1201, cancelCallAction, immutableFlags);
            
            PendingIntent fullscreenCallPendingIntent = null;
            PendingIntent contentIntent = null;
            
            if (answerCallIntent != null) {
                receiveCallPendingIntent = PendingIntent.getActivity(getApplicationContext(), 1200, answerCallIntent, mutableFlags);
                fullscreenCallPendingIntent = PendingIntent.getActivity(getApplicationContext(), 1202, answerCallIntent, mutableFlags);
                contentIntent = PendingIntent.getActivity(getApplicationContext(), 1203, answerCallIntent, mutableFlags);
            }

            notificationBuilder = null;
            Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, INCOMING_CHANNEL_ID)
                    .setContentTitle(displayName)
                    .setContentText("Incoming VoIP call")
                    .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setSound(alarmSound)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .addAction(new NotificationCompat.Action.Builder(
                            android.R.drawable.ic_menu_close_clear_cancel,
                            "Reject",
                            cancelCallPendingIntent).build())
                    .addAction(new NotificationCompat.Action.Builder(
                            android.R.drawable.ic_menu_call,
                            "Answer",
                            receiveCallPendingIntent).build());
            
            if (contentIntent != null) {
                builder.setContentIntent(contentIntent);
            }
            
            if (fullscreenCallPendingIntent != null) {
                builder.setFullScreenIntent(fullscreenCallPendingIntent, true);
                
                if (Build.VERSION.SDK_INT >= 34) {
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) {
                        boolean canUseFullScreenIntent = nm.canUseFullScreenIntent();
                        Log.d("VoipForegroundService", "Can use full screen intent: " + canUseFullScreenIntent);
                        if (!canUseFullScreenIntent) {
                            Log.w("VoipForegroundService", "Full screen intent permission not granted! Go to Settings > Apps > Your App > Notifications > Full screen intent");
                        }
                    }
                }
            }
            
            notificationBuilder = builder;
            long[] pattern = {0, 100, 1000, 300};
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            AudioManager am = (AudioManager) VoipForegroundService.this.getSystemService(Context.AUDIO_SERVICE);
            if (am.getRingerMode() != AudioManager.RINGER_MODE_SILENT)
            {
                vibrator.vibrate(pattern, 0);
            }
            try
            {
                Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                if (sound != null) {
                    ringtone.setDataSource(getApplicationContext(), sound);
                    ringtone.setAudioStreamType(AudioManager.STREAM_RING);
                    ringtone.prepare();
                    ringtone.setLooping(true);
                    ringtone.start();
                }
            }
            catch (Exception e)
            {
                Log.d("VoipForegroundService","Error playing ringtone: "+e.toString());

            }

            createIncomingChannel();
            startForeground(120, notificationBuilder.build());



        } catch (Exception e) {
            e.printStackTrace();
            Log.d("VoipForegroundService","2 "+e.toString());

        }

    }
    public void build_answered_call_notification()
    {
        stop_ringtone();
        stopSelf();
    }


    public void stop_ringtone()
    {
        try {
            ringtone.stop();
            ringtone.release();
            ringtone = null;
            vibrator.cancel();

        }
        catch (Exception e)
        {

        }
    }
    public void createOngoingChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            NotificationChannel channel = new NotificationChannel(ONGOING_CHANNEL_ID, ONGOING_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(ONGOING_CHANNEL_NAME);

            channel.setSound(null,null);
            Objects.requireNonNull(getApplicationContext().getSystemService(NotificationManager.class)).createNotificationChannel(channel);

        }
    }
    public void createIncomingChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            NotificationChannel channel = new NotificationChannel(INCOMING_CHANNEL_ID, INCOMING_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription(INCOMING_CHANNEL_NAME);
            channel.setSound(null,null);
            channel.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
            channel.setShowBadge(true);
            channel.enableVibration(true);
            channel.setBypassDnd(true);

            Objects.requireNonNull(getApplicationContext().getSystemService(NotificationManager.class)).createNotificationChannel(channel);

        }
    }

}