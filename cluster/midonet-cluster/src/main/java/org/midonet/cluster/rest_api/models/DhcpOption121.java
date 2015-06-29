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

@ZoomClass(clazz = Topology.Dhcp.Opt121Route.class)
public class DhcpOption121 extends ZoomObject {

    @JsonIgnore
    @ZoomField(name = "dst_subnet", converter = IPSubnetUtil.Converter.class)
    public IPSubnet<?> destinationSubnet;
    public String destinationPrefix;
    public int destinationLength;
    @ZoomField(name = "gateway", converter = IPAddressUtil.Converter.class)
    public String gatewayAddr;

    @JsonIgnore
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
                IPSubnet.fromString(destinationPrefix + "/" + destinationLength);
        }
    }
}
