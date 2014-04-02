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

            if (!Objects.equal(loadBalancerId, data.loadBalancerId))
                return false;
            if (!Objects.equal(poolId, data.poolId))
                return false;
            if (!Objects.equal(address, data.address))
                return false;
            if (protocolPort != data.protocolPort)
                return false;
            if (!Objects.equal(sessionPersistence,
                    data.sessionPersistence))
                return false;
            if (adminStateUp != adminStateUp)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = loadBalancerId != null ?
                    loadBalancerId.hashCode() : 0;
            result = 31 * result
                    + (poolId != null ? poolId.hashCode() : 0);
            result = 31 * result
                    + (address != null ? address.hashCode() : 0);
            result = 31 * result + protocolPort;
            result = 31 * result + (sessionPersistence != null ?
                    sessionPersistence.hashCode() : 0);
            result = 31 * result + (adminStateUp ? 1 : 0);

            return result;
        }

    }
}
