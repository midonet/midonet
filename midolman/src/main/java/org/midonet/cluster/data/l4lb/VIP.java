/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.cluster.data.l4lb;

import com.google.common.base.Objects;
import org.midonet.cluster.data.Entity;

import java.util.UUID;

public class VIP
        extends Entity.Base<UUID, VIP.Data, VIP>  {

    public final static String VIP_SOURCE_IP = "SOURCE_IP";
    public final static int VIP_STICKY_TIMEOUT_SECONDS = 86400;

    public VIP() {
        this(null, new Data());
    }

    public VIP(UUID id) {
        this(id, new Data());
    }

    public VIP(UUID uuid, Data data) {
        super(uuid, data);
    }

    public UUID getLoadBalancerId() {
        return getData().loadBalancerId;
    }

    public VIP setLoadBalancerId(UUID loadBalancerId) {
        getData().loadBalancerId = loadBalancerId;
        return self();
    }

    public UUID getPoolId() {
        return getData().poolId;
    }

    public VIP setPoolId(UUID poolId) {
        getData().poolId = poolId;
        return self();
    }

    public String getAddress() {
        return getData().address;
    }

    public VIP setAddress(String address) {
        getData().address = address;
        return self();
    }

    public int getProtocolPort() {
        return getData().protocolPort;
    }

    public VIP setProtocolPort(int protocolPort) {
        getData().protocolPort = protocolPort;
        return self();
    }

    public String getSessionPersistence() {
        return getData().sessionPersistence;
    }

    public VIP setSessionPersistence(String sessionPersistence) {
        getData().sessionPersistence = sessionPersistence;
        return self();
    }

    public boolean getAdminStateUp() {
        return getData().adminStateUp;
    }

    public VIP setAdminStateUp(boolean adminStateUp) {
        getData().adminStateUp = adminStateUp;
        return self();
    }

    @Override
    public VIP self() {
        return this;
    }

    public static class Data {
        private UUID loadBalancerId;
        private UUID poolId;
        private String address;
        private int protocolPort;
        private String sessionPersistence;
        private boolean adminStateUp = true;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Data data = (Data) o;

            return Objects.equal(loadBalancerId, data.loadBalancerId) &&
                    Objects.equal(poolId, data.poolId) &&
                    Objects.equal(address, data.address) &&
                    protocolPort == data.protocolPort &&
                    Objects.equal(sessionPersistence,
                            data.sessionPersistence) &&
                    adminStateUp == data.adminStateUp;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(loadBalancerId, poolId, address,
                    protocolPort, sessionPersistence, adminStateUp);
        }

    }
}
