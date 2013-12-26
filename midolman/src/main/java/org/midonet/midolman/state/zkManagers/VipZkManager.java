/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman.state.zkManagers;

import com.google.common.base.Objects;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

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

    public void create(VipConfig config, UUID vipId)
            throws StateAccessException, SerializationException {
        zk.addPersistent(paths.getVipPath(vipId),
                serializer.serialize(config));
    }

    public void delete(UUID id)
            throws SerializationException, StateAccessException {
        zk.delete(paths.getVipPath(id));
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

    public void update(UUID id, VipConfig config)
        throws StateAccessException, SerializationException {
        VipConfig oldConfig = get(id);
        if (!oldConfig.equals(config)) {
            zk.update(paths.getVipPath(id), serializer.serialize(config));
        }
    }
}
