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
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Objects;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.rest_api.neutron.models.Pool;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.DirectoryCallback;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.PoolHealthMonitorMappingStatus;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.l4lb.LBStatus;
import org.midonet.midolman.state.l4lb.PoolLBMethod;
import org.midonet.midolman.state.l4lb.PoolProtocol;

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

        // The load balancer ID is not part of a Neutron Pool because the
        // the load balancer object only exists in our internal representation.
        public PoolConfig(Pool pool, UUID loadBalancerId) {
            this.loadBalancerId = loadBalancerId;
            this.adminStateUp = pool.adminStateUp;
            if (pool.healthMonitors != null && pool.healthMonitors.size() > 0) {
                this.healthMonitorId = pool.healthMonitors.get(0);
                this.mappingStatus
                    = PoolHealthMonitorMappingStatus.PENDING_CREATE;
            }
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

    public List<UUID> getAll() throws StateAccessException {
        return getUuidList(paths.getPoolsPath());
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
}
