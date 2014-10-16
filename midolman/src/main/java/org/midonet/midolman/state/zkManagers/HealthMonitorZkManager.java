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

import com.google.common.base.Objects;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;

import org.midonet.cluster.data.neutron.loadbalancer.HealthMonitor;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.l4lb.HealthMonitorType;
import org.midonet.midolman.state.l4lb.LBStatus;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

import static java.util.Arrays.asList;

/**
 * Class to manage the HealthMonitor ZooKeeper data.
 */
public class HealthMonitorZkManager extends
        AbstractZkManager<UUID, HealthMonitorZkManager.HealthMonitorConfig> {

    private final static Logger log = LoggerFactory
            .getLogger(HealthMonitorZkManager.class);

    public static class HealthMonitorConfig extends BaseConfig {

        public HealthMonitorType type;
        public int delay;
        public int timeout;
        public int maxRetries;
        public boolean adminStateUp;
        public LBStatus status;

        public HealthMonitorConfig() {
            super();
        }

        public HealthMonitorConfig(HealthMonitorType type,
                                   int delay,
                                   int timeout,
                                   int maxRetries,
                                   boolean adminStateUp,
                                   LBStatus status) {
            this.type = type;
            this.delay = delay;
            this.timeout = timeout;
            this.maxRetries = maxRetries;
            this.adminStateUp = adminStateUp;
            this.status = status;
        }

        public HealthMonitorConfig(HealthMonitor healthMonitor) {
            this.type = HealthMonitorType.TCP;
            this.delay = healthMonitor.delay;
            this.timeout = healthMonitor.timeout;
            this.maxRetries = healthMonitor.maxRetries;
            this.adminStateUp = healthMonitor.adminStateUp;
            this.status = LBStatus.ACTIVE;
            this.id = healthMonitor.id;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(type, delay, timeout, maxRetries,
                    adminStateUp, status);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || !getClass().equals(o.getClass()))
                return false;

            HealthMonitorConfig that = (HealthMonitorConfig) o;

            return type == that.type &&
                    delay == that.delay &&
                    timeout == that.timeout &&
                    maxRetries == that.maxRetries &&
                    adminStateUp == that.adminStateUp &&
                    status == that.status;
        }
    }

    public HealthMonitorZkManager(ZkManager zk, PathBuilder paths,
                           Serializer serializer) {
        super(zk, paths, serializer);
    }

    @Override
    protected String getConfigPath(UUID id) {
        return paths.getHealthMonitorPath(id);
    }

    @Override
    protected Class<HealthMonitorConfig> getConfigClass() {
        return HealthMonitorConfig.class;
    }

    public List<Op> prepareCreate(UUID id, HealthMonitorConfig config)
            throws SerializationException {
        return asList(simpleCreateOp(id, config),
                      Op.create(paths.getHealthMonitorPoolsPath(id), null,
                              Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
    }

    public List<Op> prepareUpdate(UUID id, HealthMonitorConfig config)
            throws SerializationException {
        return asList(simpleUpdateOp(id, config));
    }

    public List<Op> prepareDelete(UUID id) {
        return asList(Op.delete(paths.getHealthMonitorPoolsPath(id), -1),
                Op.delete(paths.getHealthMonitorPath(id), -1));
    }

   public List<UUID> getPoolIds(UUID id)
            throws StateAccessException, SerializationException {
        return getUuidList(paths.getHealthMonitorPoolsPath(id));
    }

    public List<Op> prepareAddPool(UUID id, UUID poolId) {
        return asList(Op.create(
                paths.getHealthMonitorPoolPath(id, poolId), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
    }

    public List<Op> prepareRemovePool(UUID id, UUID poolId) {
        return asList(
                Op.delete(paths.getHealthMonitorPoolPath(id, poolId), -1));
    }
}
