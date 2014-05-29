/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.state.zkManagers;

import com.google.common.base.Objects;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.codehaus.jackson.annotate.JsonIgnore;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.DirectoryCallback;
import org.midonet.midolman.state.DirectoryCallbackFactory;
import org.midonet.midolman.state.l4lb.PoolLBMethod;
import org.midonet.midolman.state.l4lb.LBStatus;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.PoolHealthMonitorMappingStatus;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.l4lb.PoolProtocol;
import org.midonet.midolman.state.zkManagers.HealthMonitorZkManager.HealthMonitorConfig;
import org.midonet.midolman.state.zkManagers.LoadBalancerZkManager.LoadBalancerConfig;
import org.midonet.midolman.state.zkManagers.PoolMemberZkManager.PoolMemberConfig;
import org.midonet.midolman.state.zkManagers.VipZkManager.VipConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static java.util.Arrays.asList;

/**
 * Class to manage the pool ZooKeeper data.
 */
public class PoolZkManager
        extends AbstractZkManager<UUID, PoolZkManager.PoolConfig> {

    private final static Logger log = LoggerFactory
            .getLogger(PoolZkManager.class);

    public static class PoolConfig extends BaseConfig {

        public UUID loadBalancerId;
        public UUID healthMonitorId;
        public PoolProtocol protocol = PoolProtocol.TCP;
        public PoolLBMethod lbMethod = PoolLBMethod.ROUND_ROBIN;
        public boolean adminStateUp;
        public LBStatus status;
        public PoolHealthMonitorMappingStatus mappingStatus;

        public PoolConfig() {
            super();
        }

        public PoolConfig(UUID loadBalancerId,
                          UUID healthMonitorId,
                          PoolProtocol protocol,
                          PoolLBMethod lbMethod,
                          boolean adminStateUp,
                          LBStatus status,
                          PoolHealthMonitorMappingStatus mappingStatus) {
            this.loadBalancerId = loadBalancerId;
            this.healthMonitorId = healthMonitorId;
            this.protocol = protocol;
            this.adminStateUp = adminStateUp;
            this.lbMethod = lbMethod;
            this.status = status;
            this.mappingStatus = mappingStatus;
        }

        /**
         * Check if this pool config is in the transition to the regular
         * states, which are "ACTIVE" or "INACTIVE", triggered by the
         * health monitor actors.
         *
         * @return true if mappingStatus is not "ACTIVE" or "INACTIVE".
         * @throws SerializationException
         * @throws StateAccessException
         */
        @JsonIgnore
        public boolean isImmutable()
                throws SerializationException, StateAccessException {
            return (this.mappingStatus ==
                    PoolHealthMonitorMappingStatus.PENDING_CREATE ||
                    this.mappingStatus ==
                            PoolHealthMonitorMappingStatus.PENDING_DELETE ||
                    this.mappingStatus ==
                            PoolHealthMonitorMappingStatus.PENDING_UPDATE);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || !getClass().equals(o.getClass()))
                return false;

            PoolConfig that = (PoolConfig) o;

            return Objects.equal(loadBalancerId, that.loadBalancerId) &&
                    Objects.equal(healthMonitorId, that.healthMonitorId) &&
                    protocol == that.protocol &&
                    lbMethod == that.lbMethod &&
                    adminStateUp == that.adminStateUp &&
                    status == that.status &&
                    mappingStatus == that.mappingStatus;

        }

        @Override
        public int hashCode() {
            return Objects.hashCode(loadBalancerId, healthMonitorId, protocol,
                    lbMethod, adminStateUp, status, mappingStatus);
        }
    }

    public static class PoolHealthMonitorMappingConfig {

        public static class LoadBalancerConfigWithId {
            public UUID persistedId;
            public LoadBalancerConfig config;

            public LoadBalancerConfigWithId() {} // Needed for serialization.

            public LoadBalancerConfigWithId(LoadBalancerConfig config) {
                this.config = config;
                this.persistedId = config.id;
            }
        }

        public static class VipConfigWithId {
            public UUID persistedId;
            public VipConfig config;

            public VipConfigWithId() {} // Needed for serialization.

            public VipConfigWithId(VipConfig config) {
                this.config = config;
                this.persistedId = config.id;
            }
        }

        public static class PoolMemberConfigWithId {
            public UUID persistedId;
            public PoolMemberConfig config;

            public PoolMemberConfigWithId() {} // Needed for serialization.

            public PoolMemberConfigWithId(PoolMemberConfig config) {
                this.config = config;
                this.persistedId = config.id;
            }
        }

        public static class HealthMonitorConfigWithId {
            public UUID persistedId;
            public HealthMonitorConfig config;

            public HealthMonitorConfigWithId() {} // Needed for serialization.

            public HealthMonitorConfigWithId(HealthMonitorConfig config) {
                this.config = config;
                this.persistedId = config.id;
            }
        }

        public LoadBalancerConfigWithId loadBalancerConfig;
        public List<VipConfigWithId> vipConfigs;
        public List<PoolMemberConfigWithId> poolMemberConfigs;
        public HealthMonitorConfigWithId healthMonitorConfig;

        public PoolHealthMonitorMappingConfig() {} // Needed for serialization.

        public PoolHealthMonitorMappingConfig(
                LoadBalancerConfigWithId loadBalancerConfig,
                List<VipConfigWithId> vipConfigs,
                List<PoolMemberConfigWithId> poolMemberConfigs,
                HealthMonitorConfigWithId healthMonitorConfig) {
            this.loadBalancerConfig = loadBalancerConfig;
            this.vipConfigs = vipConfigs;
            this.poolMemberConfigs = poolMemberConfigs;
            this.healthMonitorConfig = healthMonitorConfig;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || !getClass().equals(o.getClass()))
                return false;

            PoolHealthMonitorMappingConfig that =
                    (PoolHealthMonitorMappingConfig) o;

            return Objects.equal(loadBalancerConfig,
                            that.loadBalancerConfig) &&
                    Objects.equal(vipConfigs, that.vipConfigs) &&
                    Objects.equal(poolMemberConfigs, that.poolMemberConfigs) &&
                    Objects.equal(healthMonitorConfig,
                            that.healthMonitorConfig);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(loadBalancerConfig, vipConfigs,
                    poolMemberConfigs, healthMonitorConfig);
        }
    }

    public PoolZkManager(ZkManager zk, PathBuilder paths,
                           Serializer serializer) {
        super(zk, paths, serializer);
    }

    @Override
    protected String getConfigPath(UUID id) {
        return paths.getPoolPath(id);
    }

    @Override
    protected Class<PoolConfig> getConfigClass() {
        return PoolConfig.class;
    }

    public List<Op> prepareCreate(UUID id, PoolConfig config)
            throws SerializationException {
        return asList(
                simpleCreateOp(id, config),
                zk.getPersistentCreateOp(paths.getPoolMembersPath(id), null),
                zk.getPersistentCreateOp(paths.getPoolVipsPath(id), null));
    }

    public List<Op> prepareUpdate(UUID id, PoolConfig config)
            throws SerializationException {
        return asList(simpleUpdateOp(id, config));
    }

    public List<Op> prepareDelete(UUID id) {
        return asList(
                Op.delete(paths.getPoolVipsPath(id), -1),
                Op.delete(paths.getPoolMembersPath(id), -1),
                Op.delete(paths.getPoolPath(id), -1));
    }

    public List<UUID> getMemberIds(UUID id) throws StateAccessException {
        return getUuidList(paths.getPoolMembersPath(id));
    }

    public List<UUID> getVipIds(UUID id) throws StateAccessException {
        return getUuidList(paths.getPoolVipsPath(id));
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

    public void getPoolMemberIdListAsync(UUID poolId,
                                  final DirectoryCallback<Set<UUID>>
                                          poolMemberContentsCallback,
                                  Directory.TypedWatcher watcher) {
        getUUIDSetAsync(paths.getPoolMembersPath(poolId),
                        poolMemberContentsCallback, watcher);
    }

    public boolean existsPoolHealthMonitorMapping(UUID poolId,
                                                  UUID healthMonitorId)
        throws StateAccessException, SerializationException {
        String mappingPath = paths.getPoolHealthMonitorMappingsPath(
                poolId, healthMonitorId);
        return zk.exists(mappingPath);
    }

    public PoolHealthMonitorMappingConfig
        getPoolHealthMonitorMapping(UUID poolId, UUID healthMonitorId,
                                    Runnable watcher)
            throws StateAccessException, SerializationException {
        String mappingPath = paths.getPoolHealthMonitorMappingsPath(
                poolId, healthMonitorId);
        byte[] data = zk.get(mappingPath, watcher);
        return serializer.deserialize(
                data, PoolHealthMonitorMappingConfig.class);

    }

    public PoolHealthMonitorMappingConfig
        getPoolHealthMonitorMapping(UUID poolId, UUID healthMonitorId)
            throws StateAccessException, SerializationException {
        return getPoolHealthMonitorMapping(poolId, healthMonitorId, null);
    }

    public void getPoolHealthMonitorMappingsAsync(
            final DirectoryCallback<Map<UUID, UUID>> cb,
            Directory.TypedWatcher watcher) {
                String path = paths.getPoolHealthMonitorMappingsPath();
                zk.asyncGetChildren(path,
                    DirectoryCallbackFactory.transform(cb,
                            splitStrSetToUuidUuidMap),
                        watcher);
    }

    public void getPoolHealthMonitorConfDataAsync(
            UUID poolId, final UUID hmId,
            final DirectoryCallback<PoolHealthMonitorMappingConfig> cb,
            Directory.TypedWatcher watcher) {
        getAsync(paths.getPoolHealthMonitorMappingsPath(poolId, hmId),
                PoolHealthMonitorMappingConfig.class, cb, watcher);
    }
}
