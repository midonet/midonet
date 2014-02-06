/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.state.zkManagers;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.common.base.Objects;
import org.apache.zookeeper.ZooDefs;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Class to manage the pool ZooKeeper data.
 */
public class PoolZkManager extends AbstractZkManager {

    private final static Logger log = LoggerFactory
            .getLogger(PoolZkManager.class);

    public static class PoolConfig {

        public String name;
        public String description;
        public UUID healthMonitorId;
        public UUID subnetId;
        public String protocol;
        public String lbMethod;
        public boolean adminStateUp;
        public String status;

        public PoolConfig() {
            super();
        }

        public PoolConfig(String name,
                          String description,
                          UUID subnetId,
                          String protocol,
                          String lbMethod,
                          boolean adminStateUp,
                          String status) {
            this.name = name;
            this.description = description;
            this.subnetId = subnetId;
            this.protocol = protocol;
            this.adminStateUp = adminStateUp;
            this.lbMethod = lbMethod;
            this.status = status;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            PoolConfig that = (PoolConfig) o;

            if (!Objects.equal(name, that.name)) return false;
            if (!Objects.equal(description, that.description)) return false;
            if (!Objects.equal(subnetId, that.subnetId)) return false;
            if (!Objects.equal(protocol, that.protocol)) return false;
            if (!Objects.equal(lbMethod, that.lbMethod)) return false;
            if (adminStateUp != that.adminStateUp) return false;
            if (!Objects.equal(status, that.status)) return false;

            return true;
        }
    }

    PoolMemberZkManager poolMemberZkManager;
    HealthMonitorZkManager healthMonitorZkManager;

    public PoolZkManager(ZkManager zk, PathBuilder paths,
                           Serializer serializer) {
        super(zk, paths, serializer);
    }

    public PoolZkManager(Directory dir, String basePath,
                           Serializer serializer) {
        this(new ZkManager(dir), new PathBuilder(basePath), serializer);
    }

    public void update(UUID id, PoolConfig config) throws StateAccessException,
            SerializationException {
        PoolConfig oldConfig = get(id);
        if (!config.equals(oldConfig)) {
            zk.update(paths.getRouterPath(id), serializer.serialize(config));
        }
    }

    public void create(PoolConfig config, UUID poolId)
            throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.create(paths.getPoolPath(poolId),
                          serializer.serialize(config),
                          ZooDefs.Ids.OPEN_ACL_UNSAFE,
                          CreateMode.PERSISTENT));
        ops.add(Op.create(paths.getPoolMembersPath(poolId),
                          serializer.serialize(config),
                          ZooDefs.Ids.OPEN_ACL_UNSAFE,
                          CreateMode.PERSISTENT));
        zk.multi(ops);
    }

    public void delete(UUID poolId) throws SerializationException,
            StateAccessException {
        List<Op> ops = new ArrayList<Op>();
        Set<String> poolMembers = zk.getChildren(
                paths.getPoolMembersPath(poolId), null);
        for (String poolMember : poolMembers) {
            // disassociate this pool from the pool member.
            poolMemberZkManager.clearPoolMemberPoolId(UUID.fromString(poolMember));
            ops.add(Op.delete(
                        paths.getPoolMemberPath(
                                poolId, UUID.fromString(poolMember)), -1));
        }
        ops.add(Op.delete(paths.getPoolMembersPath(poolId), -1));
        ops.add(Op.delete(paths.getPoolPath(poolId), -1));
        zk.multi(ops);
    }

    public boolean exists(UUID id) throws StateAccessException {
        return zk.exists(paths.getPoolPath(id));
    }

    public PoolConfig get(UUID id) throws StateAccessException,
            SerializationException {
        return get(id, null);
    }

    public PoolConfig get(UUID id, Runnable watcher)
            throws StateAccessException, SerializationException {
        byte[] data = zk.get(paths.getPoolPath(id), watcher);
        return serializer.deserialize(data, PoolConfig.class);
    }

    public void clearHealthMonitorId(UUID id)
            throws StateAccessException, SerializationException {
        PoolConfig config = get(id);
        config.healthMonitorId = null;
        update(id, config);
    }
}
