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
import org.apache.zookeeper.ZooDefs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;

import static java.util.Arrays.asList;

/**
 * Class to manage the PoolMember ZooKeeper data.
 */
public class PoolMemberZkManager extends AbstractZkManager {

    private final static Logger log = LoggerFactory
            .getLogger(PoolMemberZkManager.class);

    public static class PoolMemberConfig {

        public UUID poolId;
        public String address;
        public int protocolPort;
        public int weight;
        public boolean adminStateUp;
        public String status;

        public PoolMemberConfig() {
            super();
        }

        public PoolMemberConfig(UUID poolId,
                                String address,
                                int protocolPort,
                                int weight,
                                boolean adminStateUp,
                                String status) {
            this.poolId = poolId;
            this.address = address;
            this.protocolPort = protocolPort;
            this.weight = weight;
            this.adminStateUp = adminStateUp;
            this.status = status;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            PoolMemberConfig that = (PoolMemberConfig) o;

            if (!Objects.equal(poolId, that.poolId)) return false;
            if (!Objects.equal(address, that.address)) return false;
            if (protocolPort != that.protocolPort) return false;
            if (weight != that.weight) return false;
            if (adminStateUp != that.adminStateUp) return false;
            if (!Objects.equal(status, that.status)) return false;

            return true;
        }
    }

    public PoolMemberZkManager(ZkManager zk, PathBuilder paths,
                               Serializer serializer) {
        super(zk, paths, serializer);
    }

    public PoolMemberZkManager(Directory dir, String basePath,
                               Serializer serializer) {
        this(new ZkManager(dir), new PathBuilder(basePath), serializer);
    }

    public List<Op> prepareUpdate(UUID id, PoolMemberConfig config)
            throws SerializationException {
        return asList(Op.setData(
                paths.getPoolMemberPath(id), serializer.serialize(config), -1));
    }

    public List<Op> prepareCreate(UUID id, PoolMemberConfig config)
            throws SerializationException {
        return asList(Op.create(
                paths.getPoolMemberPath(id), serializer.serialize(config),
                ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
    }

    public List<Op> prepareDelete(UUID id) throws SerializationException,
            StateAccessException {
        return asList(Op.delete(paths.getPoolMemberPath(id), -1));
    }

    public boolean exists(UUID id) throws StateAccessException {
        return zk.exists(paths.getPoolMemberPath(id));
    }

    public PoolMemberConfig get(UUID id) throws StateAccessException,
            SerializationException {
        return get(id, null);
    }

    public PoolMemberConfig get(UUID id, Runnable watcher)
            throws StateAccessException, SerializationException {
        byte[] data = zk.get(paths.getPoolMemberPath(id), watcher);
        return serializer.deserialize(data, PoolMemberConfig.class);
    }

    public List<Op> prepareSetPoolId(UUID id, UUID poolId)
            throws SerializationException, StateAccessException {
        PoolMemberConfig config = get(id);
        if (config.poolId == poolId)
            return new ArrayList<Op>(0);

        config.poolId = poolId;
        return prepareUpdate(id, config);
    }

}
