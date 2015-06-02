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

package org.midonet.midolman.topology.devices

import java.util.UUID

import org.midonet.midolman.topology.VirtualTopology.Device
import org.midonet.quagga.BgpdConfiguration.BgpRouter

/**
 * An implementation of the [[Device]] trait holding the information for a
 * BGP port: the router port, the router AS number, BGP networks and BGP
 * peers. The class extends the [[Device]] trait, such that it can be used
 * via the [[org.midonet.midolman.topology.VirtualTopology]].
 */
case class BgpPort(port: RouterPort, router: BgpRouter) extends Device

/** An error emitted when the BGP port is deleted. */
case class BgpPortDeleted(portId: UUID)
    extends Exception(s"BGP port $portId deleted")

/** An error emitted when the BGP router is deleted. */
case class BgpRouterDeleted(portId: UUID, routerId: UUID)
    extends Exception(s"BGP router $routerId deleted")

/** An error emitted for any throwable emitted on the BGP port observable. */
case class BgpPortError(portId: UUID, e: Throwable)
    extends Exception(s"BGP port $portId error $e")
