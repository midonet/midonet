/*
 * Copyright 3012 Midokura Europe SARL
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
