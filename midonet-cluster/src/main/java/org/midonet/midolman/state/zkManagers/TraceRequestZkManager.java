/*
 * Copyright 2015 Midokura SARL
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

import java.util.Objects;
import java.util.UUID;

import com.google.inject.Inject;

import org.midonet.cluster.data.TraceRequest;
import org.midonet.midolman.rules.Condition;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.ZkManager;
import org.midonet.nsdb.BaseConfig;

/**
 * Class to manage the trace request ZooKeeper data.
 */
public class TraceRequestZkManager
    extends AbstractZkManager<UUID, TraceRequestZkManager.TraceRequestConfig> {

    public static class TraceRequestConfig extends BaseConfig {
        public String name;
        public TraceRequest.DeviceType deviceType;
        public UUID deviceId;
        public Condition condition;
        public long creationTimestampMs;
        public long limit;
        public UUID enabledRule;

        public TraceRequestConfig() {
            super();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (other == null || !(other instanceof TraceRequestConfig)) {
                return false;
            }

            TraceRequestConfig that = (TraceRequestConfig)other;
            return Objects.equals(name, that.name)
                && deviceType == that.deviceType
                && Objects.equals(deviceId, that.deviceId)
                && Objects.equals(condition, that.condition)
                && this.creationTimestampMs == that.creationTimestampMs
                && this.limit == that.limit
                && Objects.equals(enabledRule, that.enabledRule);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, deviceType, deviceId,
                    condition, creationTimestampMs, limit, enabledRule);
        }

        @Override
        public String toString() {
            return "TraceRequestConfig{name=" + name
                + ", deviceType=" + deviceType
                + ", deviceId=" + deviceId
                + ", condition=" + condition
                + ", creationTimestampMs=" + creationTimestampMs
                + ", limit=" + limit
                + ", enabledRule=" + enabledRule + "}";
        }
    }

    @Inject
    public TraceRequestZkManager(ZkManager zk, PathBuilder paths,
                                 Serializer serializer) {
        super(zk, paths, serializer);
    }

    @Override
    public Class<TraceRequestConfig> getConfigClass() {
        return TraceRequestConfig.class;
    }

    @Override
    public String getConfigPath(UUID id) {
        return paths.getTraceRequestPath(id);
    }
}
