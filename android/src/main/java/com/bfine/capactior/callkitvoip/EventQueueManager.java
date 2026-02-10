package com.bfine.capactior.callkitvoip;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class EventQueueManager {
    private static final String TAG = "EventQueueManager";
    private static final String PREFS_NAME = "callkit_event_queue";
    private static final String KEY_EVENT_QUEUE = "event_queue";
    private static final long MAX_EVENT_AGE_MS = 30000;
    
    private static List<QueuedEvent> inMemoryQueue = new ArrayList<>();
    
    public static class QueuedEvent {
        public final String eventName;
        public final String connectionId;
        public final long timestamp;
        
        public QueuedEvent(String eventName, String connectionId, long timestamp) {
            this.eventName = eventName;
            this.connectionId = connectionId;
            this.timestamp = timestamp;
        }
    }
    
    public static void queueEvent(Context context, String eventName, String connectionId) {
        if (context == null) {
            Log.w(TAG, "Context is null, cannot queue event");
            return;
        }
        
        long timestamp = System.currentTimeMillis();
        QueuedEvent event = new QueuedEvent(eventName, connectionId, timestamp);
        
        synchronized (inMemoryQueue) {
            inMemoryQueue.add(event);
        }
        
        persistQueue(context);
        Log.d(TAG, "Queued event: " + eventName + " for connectionId: " + connectionId);
    }
    
    public static List<QueuedEvent> getQueuedEvents(Context context) {
        List<QueuedEvent> allEvents = new ArrayList<>();
        
        synchronized (inMemoryQueue) {
            allEvents.addAll(inMemoryQueue);
        }
        
        List<QueuedEvent> persistedEvents = restoreQueue(context);
        if (persistedEvents != null && !persistedEvents.isEmpty()) {
            for (QueuedEvent persistedEvent : persistedEvents) {
                boolean exists = false;
                for (QueuedEvent inMemoryEvent : allEvents) {
                    if (inMemoryEvent.connectionId.equals(persistedEvent.connectionId) &&
                        inMemoryEvent.eventName.equals(persistedEvent.eventName) &&
                        inMemoryEvent.timestamp == persistedEvent.timestamp) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    allEvents.add(persistedEvent);
                }
            }
        }
        
        return filterStaleEvents(allEvents);
    }
    
    public static void clearQueue(Context context) {
        synchronized (inMemoryQueue) {
            inMemoryQueue.clear();
        }
        
        if (context != null) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_EVENT_QUEUE, "[]").apply();
            Log.d(TAG, "Cleared event queue");
        }
    }
    
    public static void removeEvent(Context context, String eventName, String connectionId) {
        synchronized (inMemoryQueue) {
            Iterator<QueuedEvent> iterator = inMemoryQueue.iterator();
            while (iterator.hasNext()) {
                QueuedEvent event = iterator.next();
                if (event.eventName.equals(eventName) && event.connectionId.equals(connectionId)) {
                    iterator.remove();
                }
            }
        }
        
        if (context != null) {
            List<QueuedEvent> persistedEvents = restoreQueue(context);
            if (persistedEvents != null) {
                Iterator<QueuedEvent> iterator = persistedEvents.iterator();
                while (iterator.hasNext()) {
                    QueuedEvent event = iterator.next();
                    if (event.eventName.equals(eventName) && event.connectionId.equals(connectionId)) {
                        iterator.remove();
                    }
                }
                persistQueue(context, persistedEvents);
            }
        }
    }
    
    private static List<QueuedEvent> filterStaleEvents(List<QueuedEvent> events) {
        long currentTime = System.currentTimeMillis();
        List<QueuedEvent> filtered = new ArrayList<>();
        
        for (QueuedEvent event : events) {
            long age = currentTime - event.timestamp;
            if (age <= MAX_EVENT_AGE_MS) {
                filtered.add(event);
            } else {
                Log.d(TAG, "Filtered out stale event: " + event.eventName + " (age: " + age + "ms)");
            }
        }
        
        return filtered;
    }
    
    private static void persistQueue(Context context) {
        if (context == null) {
            return;
        }
        
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            JSONArray eventsArray = new JSONArray();
            
            synchronized (inMemoryQueue) {
                for (QueuedEvent event : inMemoryQueue) {
                    JSONObject eventObj = new JSONObject();
                    eventObj.put("eventName", event.eventName);
                    eventObj.put("connectionId", event.connectionId);
                    eventObj.put("timestamp", event.timestamp);
                    eventsArray.put(eventObj);
                }
            }
            
            prefs.edit().putString(KEY_EVENT_QUEUE, eventsArray.toString()).apply();
            Log.d(TAG, "Persisted " + eventsArray.length() + " events to storage");
        } catch (JSONException e) {
            Log.e(TAG, "Error persisting event queue", e);
        }
    }
    
    private static void persistQueue(Context context, List<QueuedEvent> events) {
        if (context == null) {
            return;
        }
        
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            JSONArray eventsArray = new JSONArray();
            
            for (QueuedEvent event : events) {
                JSONObject eventObj = new JSONObject();
                eventObj.put("eventName", event.eventName);
                eventObj.put("connectionId", event.connectionId);
                eventObj.put("timestamp", event.timestamp);
                eventsArray.put(eventObj);
            }
            
            prefs.edit().putString(KEY_EVENT_QUEUE, eventsArray.toString()).apply();
            Log.d(TAG, "Persisted " + eventsArray.length() + " events to storage");
        } catch (JSONException e) {
            Log.e(TAG, "Error persisting event queue", e);
        }
    }
    
    private static List<QueuedEvent> restoreQueue(Context context) {
        if (context == null) {
            return new ArrayList<>();
        }
        
        List<QueuedEvent> events = new ArrayList<>();
        
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String queueJson = prefs.getString(KEY_EVENT_QUEUE, "[]");
            
            JSONArray eventsArray = new JSONArray(queueJson);
            for (int i = 0; i < eventsArray.length(); i++) {
                JSONObject eventObj = eventsArray.getJSONObject(i);
                String eventName = eventObj.getString("eventName");
                String connectionId = eventObj.getString("connectionId");
                long timestamp = eventObj.getLong("timestamp");
                
                events.add(new QueuedEvent(eventName, connectionId, timestamp));
            }
            
            Log.d(TAG, "Restored " + events.size() + " events from storage");
        } catch (JSONException e) {
            Log.e(TAG, "Error restoring event queue", e);
        }
        
        return events;
    }
}
