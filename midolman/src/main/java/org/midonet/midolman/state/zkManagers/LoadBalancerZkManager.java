/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman.state.zkManagers;

import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.simulation.LoadBalancer;
import org.midonet.midolman.state.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Class to manage the LoadBalancer ZooKeeper data.
 */
public class LoadBalancerZkManager extends AbstractZkManager {

    private final static Logger log = LoggerFactory
            .getLogger(LoadBalancerZkManager.class);

    public static class LoadBalancerConfig {
        public boolean adminStateUp;

        public LoadBalancerConfig() {
            super();
        }

        public LoadBalancerConfig(boolean adminStateUp) {
            this.adminStateUp = adminStateUp;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            LoadBalancerConfig that = (LoadBalancerConfig) o;

            if (adminStateUp != that.adminStateUp)
                return false;

            return true;
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
            throws  StateAccessException, SerializationException {
        zk.addPersistent(paths.getLoadBalancerPath(id),
                serializer.serialize(config));
    }

    public void update(UUID id , LoadBalancerConfig config)
            throws StateAccessException, SerializationException {
        LoadBalancerConfig oldConfig = get(id);
        if (!oldConfig.equals(config)) {
            zk.update(paths.getLoadBalancerPath(id),
                    serializer.serialize(config));
        }
    }

    public void delete(UUID id) throws SerializationException,
            StateAccessException {
        zk.delete(paths.getLoadBalancerPath(id));
    }

}
