/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman.state.zkManagers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.common.base.Objects;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs;

import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.DirectoryCallback;
import org.midonet.midolman.state.DirectoryCallbackFactory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.zkManagers.HealthMonitorZkManager.HealthMonitorConfig;
import org.midonet.midolman.state.zkManagers.LoadBalancerZkManager.LoadBalancerConfig;
import org.midonet.midolman.state.zkManagers.PoolMemberZkManager.PoolMemberConfig;
import org.midonet.midolman.state.zkManagers.PoolZkManager.PoolConfig;
import org.midonet.midolman.state.zkManagers.VipZkManager.VipConfig;
import org.midonet.midolman.state.zkManagers.PoolHealthMonitorZkManager.PoolHealthMonitorConfig.HealthMonitorConfigWithId;
import org.midonet.midolman.state.zkManagers.PoolHealthMonitorZkManager.PoolHealthMonitorConfig.LoadBalancerConfigWithId;
import org.midonet.midolman.state.zkManagers.PoolHealthMonitorZkManager.PoolHealthMonitorConfig.PoolMemberConfigWithId;
import org.midonet.midolman.state.zkManagers.PoolHealthMonitorZkManager.PoolHealthMonitorConfig.VipConfigWithId;

import static com.google.common.base.Preconditions.checkNotNull;
/**
 * Class to manage the pool ZooKeeper data.
 */
public class PoolHealthMonitorZkManager
    extends
    AbstractZkManager<UUID,
        PoolHealthMonitorZkManager.PoolHealthMonitorConfig> {


    public static class PoolHealthMonitorConfig {

        public static class LoadBalancerConfigWithId {

            public UUID persistedId;
            public LoadBalancerConfig config;

            public LoadBalancerConfigWithId() {
            } // Needed for serialization.

            public LoadBalancerConfigWithId(LoadBalancerConfig config) {
                this.config = config;
                this.persistedId = config.id;
            }
        }

        public static class VipConfigWithId {

            public UUID persistedId;
            public VipConfig config;

            public VipConfigWithId() {
            } // Needed for serialization.

            public VipConfigWithId(VipConfig config) {
                this.config = config;
                this.persistedId = config.id;
            }
        }

        public static class PoolMemberConfigWithId {

            public UUID persistedId;
            public PoolMemberConfig config;

            public PoolMemberConfigWithId() {
            } // Needed for serialization.

            public PoolMemberConfigWithId(PoolMemberConfig config) {
                this.config = config;
                this.persistedId = config.id;
            }
        }

        public static class HealthMonitorConfigWithId {

            public UUID persistedId;
            public HealthMonitorConfig config;

            public HealthMonitorConfigWithId() {
            } // Needed for serialization.

            public HealthMonitorConfigWithId(HealthMonitorConfig config) {
                this.config = config;
                this.persistedId = config.id;
            }
        }

        public LoadBalancerConfigWithId loadBalancerConfig;
        public List<VipConfigWithId> vipConfigs;
        public List<PoolMemberConfigWithId> poolMemberConfigs;
        public HealthMonitorConfigWithId healthMonitorConfig;

        public PoolHealthMonitorConfig() {
        } // Needed for serialization.

        public PoolHealthMonitorConfig(
            LoadBalancerConfigWithId loadBalancerConfig,
            List<VipConfigWithId> vipConfigs,
            List<PoolMemberConfigWithId> poolMemberConfigs,
            HealthMonitorConfigWithId healthMonitorConfig) {
            this.loadBalancerConfig = loadBalancerConfig;
            this.vipConfigs = vipConfigs;
            this.poolMemberConfigs = poolMemberConfigs;
            this.healthMonitorConfig = healthMonitorConfig;
        }

        private static List<VipConfigWithId> convertVips(List<VipConfig> vips) {
            List<VipConfigWithId> vipConfs = new ArrayList<>();
            for (VipConfig vip : vips) {
                vipConfs.add(new VipConfigWithId(vip));
            }
            return vipConfs;
        }

        private static List<PoolMemberConfigWithId> convertMembers(List<PoolMemberConfig> members) {
            List<PoolMemberConfigWithId> memberConfs = new ArrayList<>();
            for (PoolMemberConfig member : members) {
                memberConfs.add(new PoolMemberConfigWithId(member));
            }
            return memberConfs;
        }

        public PoolHealthMonitorConfig(
            LoadBalancerConfig loadBalancerConfig,
            List<VipConfig> vipConfigs,
            List<PoolMemberConfig> poolMemberConfigs,
            HealthMonitorConfig healthMonitorConfig) {
            this(new LoadBalancerConfigWithId(loadBalancerConfig),
                 convertVips(vipConfigs),
                 convertMembers(poolMemberConfigs),
                 new HealthMonitorConfigWithId(healthMonitorConfig));
        }

        public void addMember(PoolMemberConfig pmConf) {
            PoolMemberConfigWithId pmConfID= new PoolMemberConfigWithId(pmConf);
            if (poolMemberConfigs == null) {
                poolMemberConfigs = new ArrayList<>();
            }
            poolMemberConfigs.add(pmConfID);
        }

        public void removeMember(UUID id) {
            for (PoolMemberConfigWithId pmConf : poolMemberConfigs) {
                if (Objects.equal(pmConf.persistedId, id)) {
                    poolMemberConfigs.remove(pmConf);
                    return;
                }
            }
        }

        public void updateMember(PoolMemberConfig pmConf) {
            removeMember(pmConf.id);
            addMember(pmConf);
        }

        public void updateHealthMonitor(HealthMonitorConfig hmConf) {
            this.healthMonitorConfig = new HealthMonitorConfigWithId(hmConf);
        }

        public void addVip(VipConfig vip) {
            if (vipConfigs == null) {
                vipConfigs = new ArrayList<>();
            }
            vipConfigs.add(new VipConfigWithId(vip));
        }

        public void removeVip(VipConfig vipConfig) {
            for (VipConfigWithId vip : vipConfigs) {
                if (Objects.equal(vip.persistedId, vipConfig.id)) {
                    vipConfigs.remove(vipConfig);
                    return;
                }
            }
        }

        public void updateVip(VipConfig vipConfig) {
            removeVip(vipConfig);
            addVip(vipConfig);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || !getClass().equals(o.getClass())) {
                return false;
            }

            PoolHealthMonitorConfig that = (PoolHealthMonitorConfig) o;

            return Objects.equal(loadBalancerConfig, that.loadBalancerConfig) &&
                   Objects.equal(vipConfigs, that.vipConfigs) &&
                   Objects.equal(poolMemberConfigs, that.poolMemberConfigs) &&
                   Objects.equal(healthMonitorConfig, that.healthMonitorConfig);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(loadBalancerConfig, vipConfigs,
                                    poolMemberConfigs, healthMonitorConfig);
        }
    }

    public PoolHealthMonitorZkManager(ZkManager zk, PathBuilder paths,
                                      Serializer serializer) {
        super(zk, paths, serializer);
    }

    public PoolHealthMonitorConfig
    getPoolHealthMonitorMapping(UUID poolId, UUID healthMonitorId,
                                Runnable watcher)
        throws StateAccessException, SerializationException {
        String mappingPath =
            paths.getPoolHealthMonitorMappingsPath(poolId, healthMonitorId);
        byte[] data = zk.get(mappingPath, watcher);
        return serializer.deserialize(data, PoolHealthMonitorConfig.class);

    }

    public PoolHealthMonitorConfig getPoolHealthMonitorMapping(UUID poolId,
                                                               UUID healthMonitorId)
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

    public void getPoolHealthMonitorConfDataAsync(UUID poolId, final UUID hmId,
        final DirectoryCallback<PoolHealthMonitorConfig> cb,
        Directory.TypedWatcher watcher) {
        getAsync(paths.getPoolHealthMonitorMappingsPath(poolId, hmId),
                 PoolHealthMonitorConfig.class, cb, watcher);
    }

    public void preparePoolHealthMonitorCreate(List<Op> ops,
        LoadBalancerConfig loadBalancerConfig, List<VipConfig> vipConfigs,
        List<PoolMemberConfig> memberConfigs,
        HealthMonitorConfig healthMonitorConfig,
        PoolConfig poolConfig)
        throws StateAccessException, SerializationException {

        String msg = "the pool config must exist to create a mapping";
        checkNotNull(poolConfig, msg);
        msg = "the pool config must have an id";
        checkNotNull(poolConfig.id, msg);
        msg = "the pool config must exist to create a mapping";
        checkNotNull(healthMonitorConfig, msg);
        msg = "the health monitor config must exist to create a mapping";
        checkNotNull(healthMonitorConfig.id, msg);

        String mappingPath = paths.getPoolHealthMonitorMappingsPath(
            poolConfig.id, healthMonitorConfig.id);

        PoolHealthMonitorConfig mappingConfig = new PoolHealthMonitorConfig(
            loadBalancerConfig, vipConfigs, memberConfigs, healthMonitorConfig);
        ops.add(Op.create(mappingPath, serializer.serialize(mappingConfig),
                          ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
    }

    public void preparePoolHealthMonitorDelete(
        List<Op> ops, UUID poolId, UUID hmId) {
        ops.add(Op.delete(paths.getPoolHealthMonitorMappingsPath(poolId, hmId),
                          -1));
    }

    public void preparePoolHealthMonitorUpdate(
        List<Op> ops, UUID poolId, PoolHealthMonitorConfig phmConfig)
        throws StateAccessException, SerializationException {
        String msg = "the mapping config should never be null for updating";
        PoolHealthMonitorConfig conf = checkNotNull(phmConfig, msg);
        msg = "The health monitor ID must not be null in a mapping update";
        UUID hmId = checkNotNull(conf.healthMonitorConfig.persistedId, msg);
        msg = "The pool ID must not be null in a mapping update";
        UUID pId = checkNotNull(poolId, msg);
        String path = paths.getPoolHealthMonitorMappingsPath(pId, hmId);
        ops.add(Op.setData(path, serializer.serialize(conf), -1));
    }

    public boolean existsPoolHealthMonitorMapping(UUID poolId,
                                                  UUID healthMonitorId)
        throws StateAccessException, SerializationException {
        String mappingPath = paths.getPoolHealthMonitorMappingsPath(
            poolId, healthMonitorId);
        return zk.exists(mappingPath);
    }

    @Override
    protected String getConfigPath(UUID id) {
        return paths.getPoolPath(id);
    }

    @Override
    protected Class<PoolHealthMonitorConfig> getConfigClass() {
        return PoolHealthMonitorConfig.class;
    }
}
