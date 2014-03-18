/*
<<<<<<< HEAD
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
=======
 * Copyright (c) 2013-2014 Midokura Europe SARL, All Rights Reserved.
>>>>>>> a09f1f4... ZkManager refactoring
 */

package org.midonet.midolman.state.zkManagers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.common.base.Objects;
import org.apache.zookeeper.Op;
import org.midonet.midolman.state.StateAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.ZkManager;

import static java.util.Arrays.asList;

/**
 * Class to manage the VIP ZooKeeper data.
 */
public class VipZkManager
        extends AbstractZkManager<UUID, VipZkManager.VipConfig> {

    private final static Logger log = LoggerFactory
            .getLogger(VipZkManager.class);

    public static class VipConfig extends BaseConfig {
        public UUID loadBalancerId;
        public UUID poolId;
        public String address;
        public int protocolPort;
        public String sessionPersistence;
        public boolean adminStateUp;

        public VipConfig() {
            super();
        }

        public VipConfig(UUID loadBalancerId,
                         UUID poolId,
                         String address,
                         int protocolPort,
                         String sessionPersistence,
                         boolean adminStateUp) {
            this.loadBalancerId = loadBalancerId;
            this.poolId = poolId;
            this.address = address;
            this.protocolPort = protocolPort;
            this.sessionPersistence = sessionPersistence;
            this.adminStateUp = adminStateUp;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            VipConfig that = (VipConfig) o;

            return Objects.equal(loadBalancerId, that.loadBalancerId) &&
                   Objects.equal(poolId, that.poolId) &&
                   Objects.equal(address, that.address) &&
                   protocolPort != that.protocolPort &&
                   Objects.equal(sessionPersistence, that.sessionPersistence) &&
                   adminStateUp != that.adminStateUp;
        }
    }

    public VipZkManager(ZkManager zk, PathBuilder paths,
                        Serializer serializer) {
        super(zk, paths, serializer);
    }

    @Override
    public Class<VipConfig> getConfigClass() {
        return VipConfig.class;
    }

    @Override
    public String getConfigPath(UUID id) {
        return paths.getVipPath(id);
    }

    public List<Op> prepareCreate(UUID id, VipConfig config)
            throws SerializationException {
        return asList(simpleCreateOp(id, config));
    }

    public List<Op> prepareUpdate(UUID id, VipConfig config)
            throws SerializationException {
        return asList(simpleUpdateOp(id, config));
    }

    public List<Op> prepareDelete(UUID id) {
        return asList(Op.delete(paths.getVipPath(id), -1));
    }

    public List<Op> prepareSetPoolId(UUID id, UUID poolId)
            throws SerializationException, StateAccessException {
        VipConfig config = get(id);
        if (config.poolId == poolId)
                return new ArrayList<>(0);

        config.poolId = poolId;
        return prepareUpdate(id, config);
    }

    public List<Op> prepareSetLoadBalancerId(UUID id, UUID loadBalancerId)
            throws SerializationException, StateAccessException {
        VipConfig config = get(id);
        if (config.loadBalancerId == loadBalancerId)
            return new ArrayList<Op>(0);

        config.loadBalancerId = loadBalancerId;
        return prepareUpdate(id, config);
    }
}
