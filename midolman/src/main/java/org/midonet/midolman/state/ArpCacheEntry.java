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

package org.midonet.midolman.state;

import org.midonet.midolman.serialization.SerializationException;
import org.midonet.packets.MAC;

public class ArpCacheEntry implements Cloneable {
    public MAC macAddr;
    public long expiry;
    public long stale;
    public long lastArp;

    public ArpCacheEntry(MAC macAddr, long expiry, long stale, long lastArp) {
        this.macAddr = macAddr;
        this.expiry = expiry;
        this.stale = stale;
        this.lastArp = lastArp;
    }

    public ArpCacheEntry clone() {
        return new ArpCacheEntry(macAddr, expiry, stale, lastArp);
    }

    public String encode() {
        return (macAddr == null ? "null" : macAddr.toString()) + ";" +
               expiry + ";" + stale + ";" + lastArp;
    }

    public static ArpCacheEntry decode(String str)
                throws SerializationException {
        String[] fields = str.split(";");
        if (fields.length != 4)
            throw new SerializationException(str, null, null);
        return new ArpCacheEntry(fields[0].equals("null") ? null
                                        : MAC.fromString(fields[0]),
                                 Long.parseLong(fields[1]),
                                 Long.parseLong(fields[2]),
                                 Long.parseLong(fields[3]));
    }

    public String toString() {
        return "ArpCacheEntry [macAddr=" + macAddr +
               ", expiry=" + expiry + ", stale=" + stale +
               ", lastArp=" + lastArp + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ArpCacheEntry that = (ArpCacheEntry) o;

        if (expiry != that.expiry) return false;
        if (lastArp != that.lastArp) return false;
        if (stale != that.stale) return false;
        if (macAddr != null ? !macAddr.equals(that.macAddr) : that.macAddr != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = macAddr != null ? macAddr.hashCode() : 0;
        result = 31 * result + (int) (expiry ^ (expiry >>> 32));
        result = 31 * result + (int) (stale ^ (stale >>> 32));
        result = 31 * result + (int) (lastArp ^ (lastArp >>> 32));
        return result;
    }
}

