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

package org.midonet.cluster.cli.commands.objects

import org.midonet.cluster.data.{ZoomObject, ZoomClass, ZoomField}
import org.midonet.cluster.models.Topology
import org.midonet.cluster.util.IPAddressUtil.{Converter => IPAddressConverter}
import org.midonet.cluster.util.MACUtil.{Converter => MACConverter}
import org.midonet.packets.{IPAddr, MAC}

@ZoomClass(clazz = classOf[Topology.Host.Interface])
@CliName(name = "interface")
class Interface extends ZoomObject with Obj {

    @ZoomField(name = "name")
    @CliName(name = "name")
    var name: String = _
    @ZoomField(name = "type")
    @CliName(name = "type", readonly = false)
    var interfaceType: InterfaceType = _
    @ZoomField(name = "mac", converter = classOf[MACConverter])
    @CliName(name = "mac", readonly = false)
    var mac: MAC = _
    @ZoomField(name = "addresses", converter = classOf[IPAddressConverter])
    @CliName(name = "addresses")
    var addresses: Set[IPAddr] = Set.empty
    @ZoomField(name = "up")
    @CliName(name = "up", readonly = false)
    var isUp: Boolean = _
    @ZoomField(name = "has_link")
    @CliName(name = "has-link", readonly = false)
    var hasLink: Boolean = _
    @ZoomField(name = "mtu")
    @CliName(name = "mtu", readonly = false)
    var mtu: Int = _
    @ZoomField(name = "endpoint")
    @CliName(name = "endpoint", readonly = false)
    var endpoint: InterfaceEndpoint = _
    @ZoomField(name = "port_type")
    @CliName(name = "port-type", readonly = false)
    var portType: DpPortType = _

    override def equals(obj: Any): Boolean = obj match {
        case interface: Interface => interface.name == name
        case _ => false
    }

    override def hashCode: Int = name.hashCode
}
