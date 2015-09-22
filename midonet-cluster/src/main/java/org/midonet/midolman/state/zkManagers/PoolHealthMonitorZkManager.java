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

import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.DirectoryCallback;
import org.midonet.midolman.state.DirectoryCallbackFactory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.zkManagers.HealthMonitorZkManager.HealthMonitorConfig;
import org.midonet.midolman.state.zkManagers.LoadBalancerZkManager.LoadBalancerConfig;
import org.midonet.midolman.state.zkManagers.PoolMemberZkManager.PoolMemberConfig;
import org.midonet.midolman.state.zkManagers.VipZkManager.VipConfig;
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

    @Override
    protected String getConfigPath(UUID id) {
        return paths.getPoolPath(id);
    }

    @Override
    protected Class<PoolHealthMonitorConfig> getConfigClass() {
        return PoolHealthMonitorConfig.class;
    }
}
