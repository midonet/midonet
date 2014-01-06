/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman.state.zkManagers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.common.base.Objects;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;

import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import static java.util.Arrays.asList;

/**
 * Class to manage the VIP ZooKeeper data.
 */
public class VipZkManager extends AbstractZkManager {

    private final static Logger log = LoggerFactory
            .getLogger(VipZkManager.class);

    public static class VipConfig {
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

            if (!Objects.equal(loadBalancerId, that.loadBalancerId))
                return false;
            if (!Objects.equal(poolId, that.poolId))
                return false;
            if (!Objects.equal(address, that.address))
                return false;
            if (protocolPort != that.protocolPort)
                return false;
            if (!Objects.equal(sessionPersistence,
                    that.sessionPersistence))
                return false;
            if (adminStateUp != that.adminStateUp)
                return false;

            return true;
        }
    }

    public VipZkManager(ZkManager zk, PathBuilder paths,
                        Serializer serializer) {
        super(zk, paths, serializer);
    }

    public VipZkManager(Directory dir, String basePath,
                        Serializer serializer) {
        this(new ZkManager(dir), new PathBuilder(basePath), serializer);
    }

    public List<Op> prepareCreate(UUID id, VipConfig config)
            throws SerializationException {
        return asList(Op.create(
                paths.getVipPath(id), serializer.serialize(config),
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
    }

    public List<Op> prepareUpdate(UUID id, VipConfig config)
            throws SerializationException {
        return asList(Op.setData(
                paths.getVipPath(id), serializer.serialize(config), -1));
    }

    public List<Op> prepareDelete(UUID id) {
        return asList(Op.delete(paths.getVipPath(id), -1));
    }

    public boolean exists(UUID id) throws StateAccessException {
        return zk.exists(paths.getVipPath(id));
    }

    public VipConfig get(UUID id)
            throws StateAccessException, SerializationException {
        return get(id, null);
    }

    public VipConfig get(UUID id, Runnable watcher)
            throws StateAccessException, SerializationException {
        byte[] data = zk.get(paths.getVipPath(id), watcher);
        return serializer.deserialize(data, VipConfig.class);
    }

    public List<Op> prepareSetPoolId(UUID id, UUID poolId)
            throws SerializationException, StateAccessException {
        VipConfig config = get(id);
        if (config.poolId == poolId)
            return new ArrayList<Op>(0);

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
