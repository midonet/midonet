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
import javax.xml.bind.annotation.XmlRootElement;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.rest_api.validation.VerifyEnumValue;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.midolman.state.l4lb.HealthMonitorType;
import org.midonet.midolman.state.l4lb.LBStatus;

@XmlRootElement
public class HealthMonitor extends UriResource {

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID id;

    @NotNull
    @VerifyEnumValue(HealthMonitorType.class)
    @ZoomField(name = "type")
    public String type;

    @ZoomField(name = "delay")
    public int delay;

    @ZoomField(name = "timeout")
    public int timeout;

    @ZoomField(name = "maxRetries")
    public int maxRetries;

    @ZoomField(name = "adminStateUp")
    public boolean adminStateUp = true;

    @VerifyEnumValue(LBStatus.class)
    @ZoomField(name = "status")
    @JsonIgnore // cannot be changed
    public String status = LBStatus.ACTIVE.toString();

    public URI getPools() {
        return relativeUri(ResourceUris.POOLS);
    }

    public URI getUri() {
        return absoluteUri(ResourceUris.HEALTH_MONITORS, id);
    }
}
