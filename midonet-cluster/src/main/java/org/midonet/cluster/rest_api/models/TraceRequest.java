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
package org.midonet.cluster.rest_api.models;

import java.net.URI;
import java.util.UUID;

import javax.validation.constraints.NotNull;

import com.google.common.base.MoreObjects;
import com.google.protobuf.Message;

import org.midonet.cluster.data.TraceRequest.DeviceType;
import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomConvert;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.util.UUIDUtil;

/* Class representing trace info */
@ZoomClass(clazz = Topology.TraceRequest.class)
public class TraceRequest extends UriResource {

    @NotNull
    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID id;

    @NotNull
    @ZoomField(name = "name")
    public String name;

    @NotNull
    public DeviceType deviceType;

    @NotNull
    public UUID deviceId;

    @NotNull
    @ZoomField(name = "condition")
    public Condition condition;

    @ZoomField(name = "create_timestamp_ms")
    public long creationTimestampMs = System.currentTimeMillis();

    @ZoomField(name = "limit")
    public long limit = Long.MAX_VALUE;

    @NotNull
    @ZoomField(name = "enabled")
    public boolean enabled;

    public TraceRequest() {
        super();
    }

    public TraceRequest(UUID id, String name, DeviceType deviceType,
                        UUID deviceId, Condition condition,
                        long creationTimestampMs,
                        long limit, boolean enabled) {
        super();
        this.id = id;
        this.name = name;
        this.deviceType = deviceType;
        this.deviceId = deviceId;
        this.condition = condition;
        this.creationTimestampMs = creationTimestampMs;
        this.limit = limit;
        this.enabled = enabled;
    }

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.TRACE_REQUESTS, id);
    }

    @Override
    public void afterFromProto(Message proto) {
        super.afterFromProto(proto);

        if (proto instanceof Topology.TraceRequest) {
            Topology.TraceRequest tr = (Topology.TraceRequest)proto;
            if (tr.hasPortId()) {
                deviceType = DeviceType.PORT;
                deviceId = UUIDUtil.fromProto(tr.getPortId());
            } else if (tr.hasNetworkId()) {
                deviceType = DeviceType.BRIDGE;
                deviceId = UUIDUtil.fromProto(tr.getNetworkId());
            } else if (tr.hasRouterId()) {
                deviceType = DeviceType.ROUTER;
                deviceId = UUIDUtil.fromProto(tr.getRouterId());
            }
        } else {
            throw new ZoomConvert.ConvertException("Message should be a Rule");
        }
    }

    @Override
    public void afterToProto(Message.Builder builder) {
        super.afterToProto(builder);

        if (builder instanceof Topology.TraceRequest.Builder) {
            Topology.TraceRequest.Builder trBuilder
                = (Topology.TraceRequest.Builder)builder;
            switch (deviceType) {
            case PORT:
                trBuilder.setPortId(UUIDUtil.toProto(deviceId));
                break;
            case BRIDGE:
                trBuilder.setNetworkId(UUIDUtil.toProto(deviceId));
                break;
            case ROUTER:
                trBuilder.setRouterId(UUIDUtil.toProto(deviceId));
                break;
            }
        } else {
            throw new ZoomConvert.ConvertException(
                    "Builder should be a TraceRequest");
        }
    }

    @Override
    public String toString() {
        MoreObjects.ToStringHelper tsh = MoreObjects.toStringHelper(this)
            .omitNullValues()
            .add("id", id)
            .add("name", name)
            .add("deviceType", deviceType)
            .add("deviceId", deviceId)
            .add("creationTimestampMs", creationTimestampMs)
            .add("limit", limit)
            .add("enabled", enabled);
        condition.addConditionToStringHelper(tsh);
        return tsh.toString();
    }
}
