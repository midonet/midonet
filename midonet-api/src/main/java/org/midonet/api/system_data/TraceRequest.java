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
package org.midonet.api.system_data;

import java.net.URI;
import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;

import javax.validation.constraints.NotNull;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;
import org.midonet.api.filter.Condition;

import org.midonet.cluster.data.TraceRequest.DeviceType;

/* Class representing trace info */
@XmlRootElement
public class TraceRequest extends UriResource {

    @NotNull
    private UUID id;

    @NotNull
    private DeviceType deviceType;

    @NotNull
    private UUID deviceId;

    @NotNull
    private Condition condition;

    @NotNull
    private boolean enabled;

    public TraceRequest() {
        super();
    }

    public TraceRequest(UUID id, DeviceType deviceType,
                        UUID deviceId, Condition condition, boolean enabled) {
        super();
        this.id = id;
        this.deviceType = deviceType;
        this.deviceId = deviceId;
        this.condition = condition;
        this.enabled = enabled;
    }

    public TraceRequest(UUID id, DeviceType deviceType,
                        UUID deviceId, Condition condition) {
        this(id, deviceType, deviceId, condition, false);
    }

    public TraceRequest(org.midonet.cluster.data.TraceRequest traceRequest) {
        super();
        this.id = traceRequest.getId();
        this.deviceType = traceRequest.getDeviceType();
        this.deviceId = traceRequest.getDeviceId();
        this.condition = new Condition();
        this.condition.setFromCondition(traceRequest.getCondition());
        this.enabled = (traceRequest.getEnabledRule() != null);
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getId() {
        return id;
    }

    public void setDeviceType(DeviceType deviceType) {
        this.deviceType = deviceType;
    }

    public DeviceType getDeviceType() {
        return deviceType;
    }

    public void setDeviceId(UUID deviceId) {
        this.deviceId = deviceId;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public void setCondition(Condition condition) {
        this.condition = condition;
    }

    public Condition getCondition() {
        return condition;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean getEnabled() {
        return enabled;
    }

    @XmlRootElement
    public static class Enablement {
        private boolean enabled = false;

        public Enablement() {}

        public Enablement(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public org.midonet.cluster.data.TraceRequest toData() {
        org.midonet.midolman.rules.Condition c = condition.makeCondition();
        return new org.midonet.cluster.data.TraceRequest()
            .setId(id)
            .setDeviceType(deviceType)
            .setDeviceId(deviceId)
            .setCondition(c);
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getTraceRequest(getBaseUri(), id);
        } else {
            return null;
        }
    }
}
