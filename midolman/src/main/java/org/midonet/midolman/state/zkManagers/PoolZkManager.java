/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.state.zkManagers;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.common.base.Objects;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;

import static java.util.Arrays.asList;

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
        public UUID vipId;
        public String protocol;
        public String lbMethod;
        public boolean adminStateUp;
        public String status;

        public PoolConfig() {
            super();
        }

        public PoolConfig(String name,
                          String description,
                          String protocol,
                          String lbMethod,
                          boolean adminStateUp,
                          String status) {
            this.name = name;
            this.description = description;
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
            if (!Objects.equal(healthMonitorId, that.healthMonitorId)) return false;
            if (!Objects.equal(vipId, that.vipId)) return false;
            if (!Objects.equal(protocol, that.protocol)) return false;
            if (!Objects.equal(lbMethod, that.lbMethod)) return false;
            if (adminStateUp != that.adminStateUp) return false;
            if (!Objects.equal(status, that.status)) return false;

            return true;
        }
    }

    public PoolZkManager(ZkManager zk, PathBuilder paths,
                           Serializer serializer) {
        super(zk, paths, serializer);
    }

    public PoolZkManager(Directory dir, String basePath,
                           Serializer serializer) {
        this(new ZkManager(dir), new PathBuilder(basePath), serializer);
    }

    public List<Op> prepareUpdate(UUID id, PoolConfig config)
            throws SerializationException {
        return asList(Op.setData(
                paths.getPoolPath(id), serializer.serialize(config), -1));
    }

    public List<Op> prepareCreate(UUID id, PoolConfig config)
            throws SerializationException {
        return asList(
                Op.create(paths.getPoolPath(id),
                        serializer.serialize(config),
                        Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.create(paths.getPoolMembersPath(id), null,
                        Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.create(paths.getPoolVipsPath(id), null,
                        Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
    }

    public List<Op> prepareDelete(UUID id) {
        return asList(
                Op.delete(paths.getPoolVipsPath(id), -1),
                Op.delete(paths.getPoolMembersPath(id), -1),
                Op.delete(paths.getPoolPath(id), -1));
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

    public Set<UUID> getMemberIds(UUID id) throws StateAccessException {
        return getChildUuids(paths.getPoolMembersPath(id));
    }

    public Set<UUID> getVipIds(UUID id) throws StateAccessException {
        return getChildUuids(paths.getPoolVipsPath(id));
    }

    public List<Op> prepareSetHealthMonitorId(UUID id, UUID healthMonitorId)
            throws StateAccessException, SerializationException {
        PoolConfig config = get(id);
        if (config.healthMonitorId == healthMonitorId)
            return Collections.EMPTY_LIST;

        config.healthMonitorId = healthMonitorId;
        return prepareUpdate(id, config);
    }

    public List<Op> prepareAddVip(UUID id, UUID vipId) {
        return asList(Op.create(
                paths.getPoolVipPath(id, vipId), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
    }

    public List<Op> prepareRemoveVip(UUID id, UUID vipId) {
        return asList(Op.delete(paths.getPoolVipPath(id, vipId), -1));
    }

    public List<Op> prepareAddMember(UUID id, UUID memberId) {
        return asList(Op.create(
                paths.getPoolMemberPath(id, memberId), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
    }

    public List<Op> prepareRemoveMember(UUID id, UUID memberId) {
        return asList(Op.delete(paths.getPoolMemberPath(id, memberId), -1));
    }
}
