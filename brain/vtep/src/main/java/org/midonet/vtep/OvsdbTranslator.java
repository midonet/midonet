/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.vtep;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;


import org.opendaylight.ovsdb.lib.notation.UUID;

import org.midonet.packets.IPv4Addr;

/**
 * Type translations to and from ovsdb-specific data models
 */
public class OvsdbTranslator {

    /**
     * Convert from Ovsdb UUIDs to Java UUIDs
     */
    static public java.util.UUID fromOvsdb(UUID id) {
        return java.util.UUID.fromString(id.toString());
    }

    /**
     * Convert from Java UUIDs to Ovsdb UUIDs
     */
    static public UUID toOvsdb(java.util.UUID uuid) {
        return new UUID(uuid.toString());
    }

    /**
     * Convert to a set of java UUIDs
     * The input set can be a null value, which will result in an empty
     * output set
     */
    static public Set<java.util.UUID> fromOvsdb(Set inSet) {
        Set<java.util.UUID> set = new HashSet<>();
        if (inSet != null) {
            for (Object id: inSet) {
                set.add(fromOvsdb((UUID)id));
            }
        }
        return set;
    }

    /**
     * Convert to a set of ovsdb UUIDs
     * The input set can be a null value, which will result in an empty
     * output set
     */
    static public Set<UUID> toOvsdb(Set<java.util.UUID> inSet) {
        Set<UUID> set = new HashSet<>();
        if (inSet != null) {
            for (java.util.UUID id: inSet) {
                set.add(toOvsdb(id));
            }
        }
        return set;
    }

    /**
     * Convert to a map of Integer - java UUIDs
     * The input map can be a null value, which will result in an empty
     * output set
     */
    static public Map<Integer, java.util.UUID> fromOvsdb(Map inMap) {
        Map<Integer, java.util.UUID> map = new HashMap<>();
        if (inMap != null) {
            for (Object k: inMap.keySet()) {
                map.put(((Long)k).intValue(), fromOvsdb((UUID)inMap.get(k)));
            }
        }
        return map;
    }

    /**
     * Convert from a set of strings to a set of the ip addresses
     */
    static public Set<IPv4Addr> fromOvsdbIpSet(Set inSet) {
        Set<IPv4Addr> set = new HashSet<>();
        if (inSet != null) {
            for (Object obj : inSet) {
                String str = (String)obj;
                if (!str.isEmpty())
                    set.add(IPv4Addr.fromString(str));
            }
        }
        return set;
    }
}
