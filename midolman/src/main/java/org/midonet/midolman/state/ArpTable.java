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

import org.midonet.cluster.backend.Directory;
import org.midonet.cluster.data.storage.model.ArpEntry;
import org.midonet.packets.IPv4Addr;

public class ArpTable extends ReplicatedMap<IPv4Addr, ArpEntry> {

    public ArpTable(Directory dir) {
        super(dir);
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
    protected String encodeValue(ArpEntry value) {
        return value.encode();
    }

    @Override
    protected ArpEntry decodeValue(String str) {
        try {
            return ArpEntry.decode(str);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
