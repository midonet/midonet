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

import javax.xml.bind.annotation.XmlRootElement;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.protobuf.Message;

import org.apache.commons.lang.StringUtils;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.util.IPAddressUtil;
import org.midonet.cluster.util.IPSubnetUtil;
import org.midonet.packets.IPSubnet;

import static java.util.Objects.hash;

@XmlRootElement
@ZoomClass(clazz = Topology.Dhcp.Opt121Route.class)
public class DhcpOption121 extends ZoomObject {

    @JsonIgnore
    @ZoomField(name = "dst_subnet", converter = IPSubnetUtil.Converter.class)
    public IPSubnet<?> destinationSubnet;
    public String destinationPrefix;
    public int destinationLength;
    @ZoomField(name = "gateway", converter = IPAddressUtil.Converter.class)
    public String gatewayAddr;

    @Override
    public void afterFromProto(Message proto) {
        if (null != destinationSubnet) {
            destinationPrefix = destinationSubnet.getAddress().toString();
            destinationLength = destinationSubnet.getPrefixLen();
        }
    }

    @JsonIgnore
    @Override
    public void beforeToProto() {
        if (StringUtils.isNotEmpty(destinationPrefix)) {
            destinationSubnet =
                IPSubnet.fromString(destinationPrefix, destinationLength);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        DhcpOption121 that = (DhcpOption121) o;

        if (destinationLength != that.destinationLength) {
            return false;
        }

        if (destinationPrefix != null
            ? !destinationPrefix.equals(that.destinationPrefix)
            : that.destinationPrefix != null) {
            return false;
        }
        if (gatewayAddr != null
            ? !gatewayAddr.equals(that.gatewayAddr)
            : that.gatewayAddr != null) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        return hash(destinationPrefix, destinationLength, gatewayAddr);
    }

}
