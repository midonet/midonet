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
package org.midonet.cluster.data;

import org.midonet.midolman.rules.Condition;

import java.util.Objects;
import java.util.UUID;

public class TraceRequest
    extends Entity.Base<UUID, TraceRequest.Data, TraceRequest> {

    public TraceRequest() {
        super(null, new Data());
    }

    public String getName() {
        return getData().name;
    }

    public TraceRequest setName(String name) {
        getData().name = name;
        return this;
    }

    public UUID getDeviceId() {
        return getData().deviceId;
    }

    public TraceRequest setDeviceId(UUID deviceId) {
        getData().deviceId = deviceId;
        return this;
    }

    public DeviceType getDeviceType() {
        return getData().deviceType;
    }

    public TraceRequest setDeviceType(DeviceType deviceType) {
        getData().deviceType = deviceType;
        return this;
    }

    public Condition getCondition() {
        return getData().condition;
    }

    public TraceRequest setCondition(Condition condition) {
        getData().condition = condition;
        return this;
    }

    public long getLimit() {
        return getData().limit;
    }

    public TraceRequest setLimit(long limit) {
        getData().limit = limit;
        return this;
    }

    public UUID getEnabledRule() {
        return getData().enabledRule;
    }

    public TraceRequest setEnabledRule(UUID enabledRule) {
        getData().enabledRule = enabledRule;
        return this;
    }

    @Override
    public TraceRequest self() {
        return this;
    }

    public enum DeviceType {
        BRIDGE, PORT, ROUTER
    }

    public static class Data  {
        public String name;
        public DeviceType deviceType;
        public UUID deviceId;
        public Condition condition;
        public long limit;
        public UUID enabledRule;

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (other == null || !(other instanceof Data)) {
                return false;
            }

            System.out.println("other " + other + " this " + this);
            Data that = (Data)other;
            return Objects.equals(name, that.name)
                && deviceType == that.deviceType
                && Objects.equals(deviceId, that.deviceId)
                && Objects.equals(condition, that.condition)
                && limit == that.limit
                && Objects.equals(enabledRule, that.enabledRule);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, deviceType, deviceId,
                                condition, limit, enabledRule);
        }

        @Override
        public String toString() {
            return "TraceRequest.Data{name=" + name
                + ", deviceType=" + deviceType
                + ", deviceId=" + deviceId
                + ", condition=" + condition
                + ", limit=" + limit
                + ", enabledRule=" + enabledRule + "}";
        }
    }
}
