/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman.state.zkManagers;

import com.google.common.base.Objects;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.InvalidStateOperationException;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

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
        zk.addPersistent(paths.getLoadBalancerPath(loadBalancerId),
                serializer.serialize(config));
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

    public void delete(UUID id) throws SerializationException,
            StateAccessException {
        zk.delete(paths.getLoadBalancerPath(id));
    }

}
