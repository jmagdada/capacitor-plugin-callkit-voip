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
    String username="", connectionId="", from="";
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

        ringtone = new MediaPlayer();
        connectionId = intent.getStringExtra("connectionId");
        username = intent.getStringExtra("username");
        from = intent.getStringExtra("from");
        
        if (connectionId == null || connectionId.isEmpty()) {
            connectionId = "";
        }
        if (username == null || username.isEmpty()) {
            username = "Unknown Caller";
        }
        if (from == null || from.isEmpty()) {
            from = username;
        }
        
        Log.d("VoipForegroundService","build_incoming_call_notification for "+username+" (connectionId: "+connectionId+")");

        try {
            Intent receiveCallAction = new Intent(getApplicationContext(), VoipForegroundServiceActionReceiver.class);
            receiveCallAction.putExtra("connectionId", connectionId);
            receiveCallAction.putExtra("username", username);
            receiveCallAction.putExtra("from", from);
            receiveCallAction.setAction("RECEIVE_CALL");

            Intent cancelCallAction = new Intent(getApplicationContext(), VoipForegroundServiceActionReceiver.class);
            cancelCallAction.putExtra("connectionId", connectionId);
            cancelCallAction.putExtra("username", username);
            cancelCallAction.putExtra("from", from);
            cancelCallAction.setAction("CANCEL_CALL");

            Intent launchAppIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (launchAppIntent != null) {
                launchAppIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | 
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP | 
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP);
                launchAppIntent.putExtra("connectionId", connectionId);
                launchAppIntent.putExtra("username", username);
                launchAppIntent.putExtra("from", from);
                launchAppIntent.putExtra("isIncomingCall", true);
            }

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }

            PendingIntent receiveCallPendingIntent = PendingIntent.getBroadcast(getApplicationContext(), 1200, receiveCallAction, flags);
            PendingIntent cancelCallPendingIntent = PendingIntent.getBroadcast(getApplicationContext(), 1201, cancelCallAction, flags);
            
            PendingIntent fullscreenCallPendingIntent = null;
            PendingIntent contentIntent = null;
            
            if (launchAppIntent != null) {
                fullscreenCallPendingIntent = PendingIntent.getActivity(getApplicationContext(), 1202, launchAppIntent, flags);
                contentIntent = PendingIntent.getActivity(getApplicationContext(), 1203, launchAppIntent, flags);
            }

            notificationBuilder = null;
            Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            
            String displayName = username;
            if (!from.equals(username) && !from.isEmpty()) {
                displayName = username + " (" + from + ")";
            }
            
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