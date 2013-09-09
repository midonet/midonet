/*
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.api.auth;

import org.codehaus.jackson.annotate.JsonProperty;
import org.midonet.api.HttpSupport;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

/**
 * An API token
 */
public class Token {

    private String key;

    private Date expires;

    public Token(){
    }

    public Token(String key, Date expires) {
        this.key = key;
        this.expires = expires;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public Date getExpires() {
        return expires;
    }

    public void setExpires(Date expires) {
        this.expires = expires;
    }

    @JsonProperty("expires")
    public String getExpiresString(){
        return getExpiresString(HttpSupport.SET_COOKIE_EXPIRES_FORMAT);
    }

    public String getExpiresString(String format) {
        String expiresGmt = null;
        if (expires != null) {
            DateFormat df = new SimpleDateFormat(format);
            df.setTimeZone(TimeZone.getTimeZone("GMT"));
            expiresGmt = df.format(expires);
        }
        return expiresGmt;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("key=");
        sb.append(key);
        sb.append(", expires=");
        sb.append(expires);
        return sb.toString();
    }
}
