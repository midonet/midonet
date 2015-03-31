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
package org.midonet.brain.services.vladimir.models;

import java.util.UUID;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.xml.bind.annotation.XmlTransient;

import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.packets.IPv4;

public class RouterPort extends Port {

    @NotNull
    @Pattern(regexp = IPv4.regex, message = "is an invalid IP format")
    protected String networkAddress;

    @Min(0)
    @Max(32)
    protected int networkLength;

    @NotNull
    @Pattern(regexp = IPv4.regex, message = "is an invalid IP format")
    protected String portAddress;

    protected String portMac;

    @ZoomField(name = "router_id", converter = UUIDUtil.Converter.class)
    @XmlTransient
    public UUID routerId;

    public RouterPort() {
        super();
    }

    @Override
    public UUID getDeviceId() {
        return routerId;
    }

    @Override
    public void setDeviceId(UUID id) {
        routerId = id;
    }
}
