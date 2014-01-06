/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman.state.zkManagers;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.common.base.Objects;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.InvalidStateOperationException;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;

import static java.util.Arrays.asList;


import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Class to manage the LoadBalancer ZooKeeper data.
 */
public class LoadBalancerZkManager extends AbstractZkManager {

    private final static Logger log = LoggerFactory
            .getLogger(LoadBalancerZkManager.class);

    public static class LoadBalancerConfig {
        public UUID routerId;
        public boolean adminStateUp;

        public LoadBalancerConfig() {
            super();
        }

        public LoadBalancerConfig(UUID routerId, boolean adminStateUp) {
            this.routerId = routerId;
            this.adminStateUp = adminStateUp;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            LoadBalancerConfig that = (LoadBalancerConfig) o;

            if (Objects.equal(routerId, that.routerId))
                return false;
            if (adminStateUp != that.adminStateUp)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(routerId, adminStateUp);
        }
    }

    public LoadBalancerZkManager(ZkManager zk, PathBuilder paths,
                                 Serializer serializer) {
        super(zk, paths, serializer);
    }

    public LoadBalancerZkManager(Directory dir, String basePath,
                                 Serializer serializer) {
        this(new ZkManager(dir), new PathBuilder(basePath),
                serializer);
    }

    public boolean exists(UUID id) throws StateAccessException {
        return zk.exists(paths.getLoadBalancerPath(id));
    }

    public LoadBalancerConfig get(UUID id) throws StateAccessException,
            SerializationException {
        return get(id, null);
    }

    public LoadBalancerConfig get(UUID id, Runnable watcher)
            throws StateAccessException, SerializationException {
        byte[] data = zk.get(paths.getLoadBalancerPath(id), watcher);
        return serializer.deserialize(data, LoadBalancerConfig.class);
    }

    public void create(UUID id, LoadBalancerConfig config)
            throws StateAccessException, SerializationException,
            InvalidStateOperationException {

        UUID loadBalancerId = checkNotNull(id, "The load balancer ID is null");

        zk.multi(asList(
                Op.create(paths.getLoadBalancerPath(loadBalancerId),
                        serializer.serialize(config),
                        Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.create(paths.getLoadBalancerVipsPath(loadBalancerId),
                        serializer.serialize(config),
                        Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)));
    }

    public void update(UUID id , LoadBalancerConfig config)
            throws StateAccessException, SerializationException,
            InvalidStateOperationException {
        LoadBalancerConfig oldConfig = get(id);
        // If `routerId` is modified, it throws the exception. The
        // load balancers should be assigned only from the router side.
        if (!Objects.equal(oldConfig.routerId, config.routerId)) {
            throw new InvalidStateOperationException("The router ID cannot " +
                    "be modified from the load balancer side.");
        } else if (!oldConfig.equals(config)) {
            zk.update(paths.getLoadBalancerPath(id),
                    serializer.serialize(config));
        }
    }

    public List<Op> prepareDelete(UUID id)
            throws SerializationException, StateAccessException {
        return asList(
                Op.delete(paths.getLoadBalancerVipsPath(id), -1),
                Op.delete(paths.getLoadBalancerPath(id), -1));
    }

    public List<Op> prepareAddVip(UUID id, UUID vipId)
            throws SerializationException, StateAccessException {
        return asList(Op.create(
                paths.getLoadBalancerVipPath(id, vipId), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
    }
    
    public Set<UUID> getVipIds(UUID id) throws StateAccessException {
        return getChildUuids(paths.getLoadBalancerVipsPath(id));
    }

    public List<Op> prepareRemoveVip(UUID id, UUID vipId) {
        return asList(Op.delete(paths.getLoadBalancerVipPath(id, vipId), -1));
    }

}
