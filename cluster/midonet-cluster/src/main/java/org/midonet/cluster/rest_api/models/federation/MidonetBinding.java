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

package org.midonet.cluster.rest_api.models.federation;

import java.util.UUID;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.xml.bind.annotation.XmlRootElement;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Federation;
import org.midonet.cluster.rest_api.validation.MessageProperty;
import org.midonet.cluster.util.IPAddressUtil;
import org.midonet.cluster.util.UUIDUtil.Converter;
import org.midonet.packets.IPv4;

import static java.util.Objects.hash;

@XmlRootElement
@ZoomClass(clazz = Federation.VxlanSegment.MidoNetBinding.class)
public class MidonetBinding extends ZoomObject {

    @ZoomField(name = "midonet_vtep_id", converter = Converter.class)
    public UUID midonetVtepId;

    @ZoomField(name = "router_id", converter = Converter.class)
    public UUID routerId;

    @ZoomField(name = "router_port_id", converter = Converter.class)
    public UUID routerPortId;

    @ZoomField(name = "vtep_port_id", converter = Converter.class)
    public UUID vtepPortId;

    @NotNull
    @Pattern(regexp = IPv4.regex, message = MessageProperty.IP_ADDR_INVALID)
    @ZoomField(name = "router_ip", converter = IPAddressUtil.Converter.class)
    public String routerIp;

    @ZoomField(name = "router_mac")
    public String routerMac;

    @Override
    public int hashCode() {
        return hash(midonetVtepId, routerId, routerPortId, vtepPortId, routerIp, routerMac);
    }

}
