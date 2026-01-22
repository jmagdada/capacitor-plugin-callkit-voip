package com.bfine.capactior.callkitvoip;

public class CallConfig {
    public final String callId;
    public final String media;
    public final String duration;
    public final int bookingId;

    public CallConfig(String callId, String media, String duration, int bookingId) {
        this.callId = callId;
        this.media = media;
        this.duration = duration;
        this.bookingId = bookingId;
    }
    
    public String getDisplayName() {
        return "Call #" + bookingId;
    }
}
