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

import java.util.UUID;

import com.google.common.base.Objects;

import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.l4lb.HealthMonitorType;
import org.midonet.midolman.state.l4lb.LBStatus;
import org.midonet.nsdb.BaseConfig;

/**
 * Class to manage the HealthMonitor ZooKeeper data.
 */
public class HealthMonitorZkManager extends
        AbstractZkManager<UUID, HealthMonitorZkManager.HealthMonitorConfig> {

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

}
