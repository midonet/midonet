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
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.packets.IPv4;

import static org.midonet.cluster.util.UUIDUtil.*;

@XmlRootElement
// TODO: needs ZOOM class
public class VIP extends ZoomObject {

    @ZoomField(name = "id", converter = Converter.class)
    public UUID id;

    @ZoomField(name = "loadBalancerId", converter = Converter.class)
    public UUID loadBalancerId;

    @NotNull
    @ZoomField(name = "poolId", converter = Converter.class)
    private UUID poolId;

    @Pattern(regexp = IPv4.regex, message = "is an invalid IP format")
    @ZoomField(name = "address")
    public String address;

    @Min(0)
    @Max(65536)
    @ZoomField(name = "protocolPort")
    public int protocolPort;

    // TODO: @VerifyEnumValue(VipSessionPersistence.class)
    @ZoomField(name = "sessionPersistence")
    public String sessionPersistence;

    @ZoomField(name = "adminStateUp")
    public boolean adminStateUp = true;

}
