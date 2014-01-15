/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.state.zkManagers;

import com.google.common.base.Objects;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Arrays.asList;

/**
 * Class to manage the HealthMonitor ZooKeeper data.
 */
public class HealthMonitorZkManager extends AbstractZkManager {

    private final static Logger log = LoggerFactory
            .getLogger(HealthMonitorZkManager.class);

    public static class HealthMonitorConfig {

        public String type;
        public int delay;
        public int timeout;
        public int maxRetries;
        public boolean adminStateUp;
        public String status;

        public HealthMonitorConfig() {
            super();
        }

        public HealthMonitorConfig(String type,
                                   int delay,
                                   int timeout,
                                   int maxRetries,
                                   boolean adminStateUp,
                                   String status) {
            this.type = type;
            this.delay = delay;
            this.timeout = timeout;
            this.maxRetries = maxRetries;
            this.adminStateUp = adminStateUp;
            this.status = status;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            HealthMonitorConfig that = (HealthMonitorConfig) o;

            if (!Objects.equal(type, that.type)) return false;
            if (delay != that.delay) return false;
            if (timeout != that.timeout) return false;
            if (maxRetries != that.maxRetries) return false;
            if (adminStateUp != that.adminStateUp) return false;
            if (!Objects.equal(status, that.status)) return false;

            return true;
        }
    }

    public HealthMonitorZkManager(ZkManager zk, PathBuilder paths,
                           Serializer serializer) {
        super(zk, paths, serializer);
    }

    public List<Op> prepareCreate(UUID id, HealthMonitorConfig config)
            throws SerializationException {
        return asList(
                Op.create(paths.getHealthMonitorPath(id),
                          serializer.serialize(config),
                          Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.create(paths.getHealthMonitorPoolsPath(id), null,
                          Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
    }

    public List<Op> prepareUpdate(UUID id, HealthMonitorConfig config)
            throws SerializationException {
        return asList(Op.setData(
                paths.getHealthMonitorPath(id),
                serializer.serialize(config), -1));
    }

    public List<Op> prepareDelete(UUID id) {
        return asList(
                Op.delete(paths.getHealthMonitorPoolsPath(id), -1),
                Op.delete(paths.getHealthMonitorPath(id), -1));
    }

    public boolean exists(UUID id) throws StateAccessException {
        return zk.exists(paths.getHealthMonitorPath(id));
    }

    public HealthMonitorConfig get(UUID id) throws StateAccessException,
            SerializationException {
        return get(id, null);
    }

    public HealthMonitorConfig get(UUID id, Runnable watcher)
            throws StateAccessException, SerializationException {
        byte[] data = zk.get(paths.getHealthMonitorPath(id), watcher);
        return serializer.deserialize(data, HealthMonitorConfig.class);
    }

    public Set<UUID> getPoolIds(UUID id)
            throws StateAccessException, SerializationException {
        return getChildUuids(paths.getHealthMonitorPoolsPath(id));
    }

    public List<Op> prepareAddPool(UUID id, UUID poolId) {
        return asList(Op.create(
                paths.getHealthMonitorPoolPath(id, poolId), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
    }

    public List<Op> prepareRemovePool(UUID id, UUID poolId) {
        return asList(
                Op.delete(paths.getHealthMonitorPoolPath(id, poolId), -1));
    }
}
