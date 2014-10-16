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

import org.apache.zookeeper.KeeperException;

import org.midonet.midolman.serialization.SerializationException;
import org.midonet.packets.IPv4Addr;

public class ArpTable extends ReplicatedMap<IPv4Addr, ArpCacheEntry> {

    public ArpTable(Directory dir) {
        super(dir);
    }

    @Override
    public ArpCacheEntry get(IPv4Addr key) {
        return super.get(key);
    }

    @Override
    public boolean containsKey(IPv4Addr key) {
        return super.containsKey(key);
    }

    @Override
    public void put(IPv4Addr key, ArpCacheEntry value) {
        super.put(key, value);
    }

    @Override
    public ArpCacheEntry removeIfOwner(IPv4Addr key)
        throws InterruptedException, KeeperException {
        return super.removeIfOwner(key);
    }

    @Override
    public synchronized boolean isKeyOwner(IPv4Addr key) {
        return super.isKeyOwner(key);
    }

    @Override
    protected String encodeKey(IPv4Addr key) {
        return key.toString();
    }

    @Override
    protected IPv4Addr decodeKey(String str) {
        return IPv4Addr.fromString(str);
    }

    @Override
    protected String encodeValue(ArpCacheEntry value) {
        return value.encode();
    }

    @Override
    protected ArpCacheEntry decodeValue(String str) {
        try {
            return ArpCacheEntry.decode(str);
        } catch (SerializationException e) {
            throw new RuntimeException(e);
        }
    }
}
