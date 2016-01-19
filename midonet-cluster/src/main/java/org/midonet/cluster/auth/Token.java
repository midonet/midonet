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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import org.midonet.util.http.HttpSupport;

/**
 * An API authentication token.
 */
public class Token {

    private static DateFormat formatter =
        new SimpleDateFormat(HttpSupport.SET_COOKIE_EXPIRES_FORMAT);

    public String key;

    @JsonIgnore
    public Date expires;

    static {
        formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public Token() { }

    public Token(String key, Date expires) {
        this.key = key;
        this.expires = expires;
    }

    @JsonProperty("expires")
    public String getExpiresString(){
        if (expires != null) {
            return formatter.format(expires);
        } else {
            return null;
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
            .add("key", key)
            .add("expires", expires)
            .toString();
    }
}
