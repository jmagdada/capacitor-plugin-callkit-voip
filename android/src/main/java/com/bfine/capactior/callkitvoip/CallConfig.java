package com.bfine.capactior.callkitvoip;

public class CallConfig {
    public final String callId;
    public final String media;
    public final String duration;
    public final String bookingId;
    public final String type;
    public final String call_type;
    public final String channel_id;

    public CallConfig(String callId, String media, String duration, String bookingId,
                      String type, String call_type, String channel_id) {
        this.callId = callId;
        this.media = media;
        this.duration = duration;
        this.bookingId = bookingId;
        this.type = type;
        this.call_type = call_type;
        this.channel_id = channel_id;
    }
    
    public String getDisplayName() {
        return "Call #" + bookingId;
    }
}
