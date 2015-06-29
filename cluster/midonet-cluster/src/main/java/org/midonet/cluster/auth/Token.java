/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.cluster.auth;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.midonet.util.http.HttpSupport;

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
