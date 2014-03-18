/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.state.zkManagers;

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
import org.midonet.midolman.state.DirectoryCallback;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.PoolMemberStatus;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;

import static java.util.Arrays.asList;

/**
 * Class to manage the PoolMember ZooKeeper data.
 */
public class PoolMemberZkManager extends
        AbstractZkManager<UUID, PoolMemberZkManager.PoolMemberConfig> {

    private final static Logger log = LoggerFactory
            .getLogger(PoolMemberZkManager.class);

    public static class PoolMemberConfig extends BaseConfig {

        public UUID poolId;
        public String address;
        public int protocolPort;
        public int weight;
        public boolean adminStateUp;
        public PoolMemberStatus status;

        public PoolMemberConfig() {
            super();
        }

        public PoolMemberConfig(UUID poolId,
                                String address,
                                int protocolPort,
                                int weight,
                                boolean adminStateUp,
                                PoolMemberStatus status) {
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

    @Override
    protected String getConfigPath(UUID id) {
        return paths.getPoolMemberPath(id);
    }

    @Override
    protected Class<PoolMemberConfig> getConfigClass() {
        return PoolMemberConfig.class;
    }

    public List<Op> prepareCreate(UUID id, PoolMemberConfig config)
            throws SerializationException {
        return asList(simpleCreateOp(id, config));
    }

    public List<Op> prepareSetPoolId(UUID id, UUID poolId)
            throws SerializationException, StateAccessException {
        assert poolId != null;
        PoolMemberConfig config = get(id);
        config.poolId = poolId;
        return prepareUpdate(id, config);
    }

    public List<Op> prepareUpdate(UUID id, PoolMemberConfig config)
            throws SerializationException {
        return asList(simpleUpdateOp(id, config));
    }

    public List<Op> prepareDelete(UUID id) {
        return asList(Op.delete(paths.getPoolMemberPath(id), -1));
    }
}
