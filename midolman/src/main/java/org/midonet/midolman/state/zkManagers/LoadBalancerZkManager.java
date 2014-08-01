/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.state.zkManagers;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.common.base.Objects;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;

import org.midonet.cluster.data.l4lb.LoadBalancer;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.DirectoryCallback;
import org.midonet.midolman.state.InvalidStateOperationException;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Arrays.asList;

/**
 * Class to manage the LoadBalancer ZooKeeper data.
 */
public class LoadBalancerZkManager extends
        AbstractZkManager<UUID, LoadBalancerZkManager.LoadBalancerConfig> {

    private final static Logger log = LoggerFactory
            .getLogger(LoadBalancerZkManager.class);

    public static class LoadBalancerConfig extends BaseConfig {
        public UUID routerId;
        public boolean adminStateUp;

        public LoadBalancerConfig() {
            super();
        }

        public LoadBalancerConfig(boolean adminStateUp) {
            this.adminStateUp = adminStateUp;
        }

        public LoadBalancerConfig(UUID routerId, boolean adminStateUp) {
            this.routerId = routerId;
            this.adminStateUp = adminStateUp;
        }

        public void setAdminStateUp(boolean adminStateUp) {
            this.adminStateUp = adminStateUp;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(routerId, adminStateUp);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || !getClass().equals(o.getClass()))
                return false;

            LoadBalancerConfig that = (LoadBalancerConfig) o;

            return adminStateUp == that.adminStateUp &&
                    Objects.equal(routerId, that.routerId);
        }
    }

    public LoadBalancerZkManager(ZkManager zk, PathBuilder paths,
                                 Serializer serializer) {
        super(zk, paths, serializer);
    }

    @Override
    protected String getConfigPath(UUID id) {
        return paths.getLoadBalancerPath(id);
    }

    @Override
    protected Class<LoadBalancerConfig> getConfigClass() {
        return LoadBalancerConfig.class;
    }

    public List<Op> prepareCreate(UUID id, LoadBalancerConfig config)
        throws SerializationException {
        UUID loadBalancerId = checkNotNull(id, "The load balancer ID is null");
        LoadBalancerConfig loadBalancerConfig
            = checkNotNull(config, "The load balancer ID is null");

        return asList(simpleCreateOp(loadBalancerId, loadBalancerConfig),
            zk.getPersistentCreateOp(
                paths.getLoadBalancerPoolsPath(loadBalancerId), null),
            zk.getPersistentCreateOp(
                paths.getLoadBalancerVipsPath(loadBalancerId), null));
    }

    public void create(UUID id, LoadBalancerConfig config)
            throws StateAccessException, SerializationException,
            InvalidStateOperationException {

        UUID loadBalancerId = checkNotNull(id, "The load balancer ID is null");

        zk.multi(asList(
                simpleCreateOp(loadBalancerId, config),
                zk.getPersistentCreateOp(
                        paths.getLoadBalancerPoolsPath(id), null),
                zk.getPersistentCreateOp(
                        paths.getLoadBalancerVipsPath(id), null)));
    }

    public void update(UUID id, LoadBalancerConfig config)
            throws StateAccessException, SerializationException,
            InvalidStateOperationException {
        LoadBalancerConfig oldConfig = get(id);
        // If `routerId` is modified, it throws the exception. The
        // load balancers should be assigned only from the router side.
        if (!Objects.equal(oldConfig.routerId, config.routerId)) {
            throw new InvalidStateOperationException("The router ID cannot " +
                    "be modified from the load balancer side.");
        } else if (!oldConfig.equals(config)) {
            zk.multi(asList(simpleUpdateOp(id, config)));
        }
    }

    public List<Op> prepareDelete(UUID id) {
        return asList(
                Op.delete(paths.getLoadBalancerVipsPath(id), -1),
                Op.delete(paths.getLoadBalancerPoolsPath(id), -1),
                Op.delete(paths.getLoadBalancerPath(id), -1));
    }

    public List<Op> prepareAddPool(UUID id, UUID poolId)
            throws SerializationException, StateAccessException {
        return asList(Op.create(
                paths.getLoadBalancerPoolPath(id, poolId), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
    }

    public List<Op> prepareAddVip(UUID id, UUID vipId)
            throws SerializationException, StateAccessException {
        return asList(Op.create(
                paths.getLoadBalancerVipPath(id, vipId), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
    }

    public Set<UUID> getPoolIds(UUID id) throws StateAccessException {
        return getUuidSet(paths.getLoadBalancerPoolsPath(id));
    }

    public Set<UUID> getVipIds(UUID id) throws StateAccessException {
        return getUuidSet(paths.getLoadBalancerVipsPath(id));
    }

    public List<Op> prepareRemovePool(UUID id, UUID poolId) {
        return asList(Op.delete(paths.getLoadBalancerPoolPath(id, poolId), -1));
    }

    public List<Op> prepareRemoveVip(UUID id, UUID vipId) {
        return asList(Op.delete(paths.getLoadBalancerVipPath(id, vipId), -1));
    }

    public List<Op> prepareSetRouterId(UUID id, UUID routerId)
            throws SerializationException, StateAccessException {
        LoadBalancerConfig config = get(id);
        config.routerId = routerId;
        return asList(simpleUpdateOp(id, config));
    }

    public void getVipIdListAsync(UUID loadBalancerId,
                                  final DirectoryCallback<Set<UUID>>
                                          vipContentsCallback,
                                  Directory.TypedWatcher watcher) {
        getUUIDSetAsync(paths.getLoadBalancerVipsPath(loadBalancerId),
                        vipContentsCallback, watcher);
    }

}
