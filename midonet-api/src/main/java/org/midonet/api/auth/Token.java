/*
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.api.auth;

import java.util.Date;

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
