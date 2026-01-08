package com.bfine.capactior.callkitvoip;

public class CallConfig {
    public final String callId;
    public final String media;
    public final String duration;
    public final int bookingId;
    public final String host;
    public final String username;
    public final String secret;

    public CallConfig(String callId, String media, String duration, int bookingId, 
                     String host, String username, String secret) {
        this.callId = callId;
        this.media = media;
        this.duration = duration;
        this.bookingId = bookingId;
        this.host = host;
        this.username = username;
        this.secret = secret;
    }
}
