/*
 * Copyright 2017 Midokura SARL
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

package org.midonet.client.dto;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

import org.midonet.cluster.rest_api.models.Condition;
import org.midonet.cluster.rest_api.models.TraceRequest;

public class DtoTraceRequest {

    public UUID id;
    public String name;
    public TraceRequest.DeviceType deviceType;
    public UUID deviceId;
    public Condition condition;
    public long creationTimestampMs;
    public long limit = Long.MAX_VALUE;
    public boolean enabled;
    public URI uri;

    public DtoTraceRequest() { }

    public DtoTraceRequest(UUID id, String name,
                           TraceRequest.DeviceType deviceType,
                           UUID deviceId, Condition condition,
                           long creationTimestampMs,
                           long limit, boolean enabled) {
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
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null) return false;
        if (!(obj instanceof DtoTraceRequest)) return false;
        DtoTraceRequest dto = (DtoTraceRequest) obj;
        return Objects.equals(id, dto.id) &&
               Objects.equals(name, dto.name) &&
               Objects.equals(deviceType, dto.deviceType) &&
               Objects.equals(deviceId, dto.deviceId) &&
               creationTimestampMs == dto.creationTimestampMs &&
               limit == dto.limit &&
               enabled == dto.enabled;
    }
}
