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
package org.midonet.cluster;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.midonet.cluster.client.VlanPortMap;

public class VlanPortMapImpl implements VlanPortMap {

        private Map<Short, UUID> dir = new HashMap<Short, UUID>();
        private Map<UUID, Short> rev = new HashMap<UUID, Short>();

        public void add(Short vlanId, UUID portId) {
            dir.put(vlanId, portId);
            rev.put(portId, vlanId);
        }

        @Override
        public Short getVlan(UUID portId) {
            return (portId == null) ? null : rev.get(portId);
        }

        @Override
        public UUID getPort(Short vlanId) {
            return (vlanId == null) ? null : dir.get(vlanId);
        }

        @Override
        public String toString() {
            return "Direct vlan->port map: {" + dir.toString() + "}";
        }

        @Override
        public boolean isEmpty() {
            return dir.isEmpty();
        }
}
