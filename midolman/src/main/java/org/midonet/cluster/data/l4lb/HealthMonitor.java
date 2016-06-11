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
package org.midonet.cluster.data.l4lb;

import java.util.UUID;

import com.google.common.base.Objects;

import org.midonet.cluster.data.Entity;
import org.midonet.midolman.state.l4lb.HealthMonitorType;
import org.midonet.midolman.state.l4lb.LBStatus;

public class HealthMonitor
        extends Entity.Base<UUID, HealthMonitor.Data, HealthMonitor>{

    public HealthMonitor() {
        this(null, new Data());
    }

    public HealthMonitor(UUID id){
        this(id, new Data());
    }

    public HealthMonitor(Data data){
        this(null, data);
    }

    public HealthMonitor(UUID uuid, Data data) {
        super(uuid, data);
    }

    protected HealthMonitor self() {
        return this;
    }

    public HealthMonitor setType(HealthMonitorType type) {
        getData().type = type;
        return self();
    }

    public HealthMonitorType getType() {
        return getData().type;
    }

    public HealthMonitor setDelay(int delay) {
        getData().delay = delay;
        return self();
    }

    public int getDelay() {
        return getData().delay;
    }

    public HealthMonitor setTimeout(int timeout) {
        getData().timeout = timeout;
        return self();
    }

    public int getTimeout() {
        return getData().timeout;
    }

    public HealthMonitor setMaxRetries(int maxRetries) {
        getData().maxRetries = maxRetries;
        return self();
    }

    public int getMaxRetries() {
        return getData().maxRetries;
    }

    public HealthMonitor setAdminStateUp(boolean adminStateUp) {
        getData().adminStateUp = adminStateUp;
        return self();
    }

    public boolean isAdminStateUp() {
        return getData().adminStateUp;
    }

    public HealthMonitor setStatus(LBStatus status) {
        getData().status = status;
        return self();
    }

    public LBStatus getStatus() {
        return getData().status;
    }

    public static class Data {
        private HealthMonitorType type = HealthMonitorType.TCP;
        private int delay;
        private int timeout;
        private int maxRetries;
        private boolean adminStateUp = true;
        private LBStatus status;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Data data = (Data) o;

            return type == data.type &&
                    delay == data.delay &&
                    timeout == data.timeout &&
                    maxRetries == data.maxRetries &&
                    adminStateUp == data.adminStateUp &&
                    status == data.status;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(type, delay, timeout, maxRetries,
                    adminStateUp, status);
        }

        @Override
        public String toString() {
            return "Data{" +
                    "type='" + type + '\'' +
                    ", delay=" + delay +
                    ", timeout=" + timeout +
                    ", maxRetries=" + maxRetries +
                    ", adminStateUp=" + adminStateUp +
                    ", status=" + status +
                    '}';
        }
    }
}
