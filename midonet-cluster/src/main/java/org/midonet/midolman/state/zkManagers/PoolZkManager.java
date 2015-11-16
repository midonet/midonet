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

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Objects;
import com.google.inject.Inject;

import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.PoolHealthMonitorMappingStatus;
import org.midonet.cluster.backend.zookeeper.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.l4lb.LBStatus;
import org.midonet.midolman.state.l4lb.PoolLBMethod;
import org.midonet.midolman.state.l4lb.PoolProtocol;
import org.midonet.nsdb.BaseConfig;

/**
 * Class to manage the pool ZooKeeper data.
 */
public class PoolZkManager
        extends AbstractZkManager<UUID, PoolZkManager.PoolConfig> {

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

    @Inject
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

    public List<UUID> getMemberIds(UUID id) throws StateAccessException {
        return getUuidList(paths.getPoolMembersPath(id));
    }

    public List<UUID> getAll() throws StateAccessException {
        return getUuidList(paths.getPoolsPath());
    }

}
