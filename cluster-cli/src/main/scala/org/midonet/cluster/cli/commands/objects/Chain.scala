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

import java.util.UUID

import org.midonet.cluster.data.{ZoomClass, ZoomField, ZoomObject}
import org.midonet.cluster.models.Topology.{Chain => TopologyChain}
import org.midonet.cluster.util.UUIDUtil.{Converter => UUIDConverter}

/** A rule chain. */
@ZoomClass(clazz = classOf[TopologyChain])
@CliName(name = "chain")
final class Chain extends ZoomObject with Obj {

    @ZoomField(name = "id", converter = classOf[UUIDConverter])
    @CliName(name = "id")
    var id: UUID = _
    @ZoomField(name = "name")
    @CliName(name = "name", readonly = false)
    var name: String = _

    @ZoomField(name = "rule_ids", converter = classOf[UUIDConverter])
    @CliName(name = "rule-ids")
    var ruleIds: Set[UUID] = _
    @ZoomField(name = "network_ids", converter = classOf[UUIDConverter])
    @CliName(name = "network-ids")
    var networkIds: Set[UUID] = _
    @ZoomField(name = "router_ids", converter = classOf[UUIDConverter])
    @CliName(name = "router-ids")
    var routerIds: Set[UUID] = _
    @ZoomField(name = "port_ids", converter = classOf[UUIDConverter])
    @CliName(name = "port-ids")
    var portIds: Set[UUID] = _

}
