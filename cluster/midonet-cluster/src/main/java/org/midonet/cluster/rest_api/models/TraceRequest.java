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

import org.midonet.cluster.data.TraceRequest.DeviceType;
import org.midonet.cluster.rest_api.ResourceUris;

/* Class representing trace info */
public class TraceRequest extends UriResource {

    @NotNull
    public UUID id;

    @NotNull
    public String name;

    @NotNull
    public DeviceType deviceType;

    @NotNull
    public UUID deviceId;

    @NotNull
    public Condition condition;

    public long creationTimestampMs = System.currentTimeMillis();

    public long limit = Long.MAX_VALUE;

    @NotNull
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
    public String toString() {
        return "TraceRequest{name=" + name
            + ", deviceType=" + deviceType
            + ", deviceId=" + deviceId
            + ", condition=" + condition
            + ", creationTimestampMs=" + creationTimestampMs
            + ", limit=" + limit
            + ", enabled=" + enabled + "}";
    }

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.TRACE_REQUESTS, id);
    }
}
