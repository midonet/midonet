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
package org.midonet.cluster.util

import org.midonet.cluster.models.Commons.IPVersion
import org.midonet.cluster.models.Neutron.NeutronSubnetOrBuilder
import org.midonet.cluster.models.Topology.DhcpOrBuilder
import org.midonet.packets.{ARP, IPv4, IPv6}

object DhcpUtil {

    implicit def asRichDhcp(dhcp: DhcpOrBuilder): RichDhcp = new RichDhcp(dhcp)

    class RichDhcp private[DhcpUtil](val dhcp: DhcpOrBuilder) extends AnyVal {
        def isIpv4 = dhcp.getSubnetAddress.getVersion == IPVersion.V4
        def isIpv6 = dhcp.getSubnetAddress.getVersion == IPVersion.V6
        def etherType = if (isIpv4) IPv4.ETHERTYPE else IPv6.ETHERTYPE
    }

    implicit def asRichNeutronSubnet(subnet: NeutronSubnetOrBuilder)
    : RichNeutronSubnet = new RichNeutronSubnet(subnet)

    class RichNeutronSubnet private[DhcpUtil](
            val subnet: NeutronSubnetOrBuilder) extends AnyVal {
        def isIpv4 = subnet.getIpVersion == 4
        def isIpv6 = subnet.getIpVersion == 6
    }
}
