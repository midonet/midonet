/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.midolman.routingprotocols

import java.util.UUID

import com.typesafe.scalalogging.Logger
import org.midonet.packets.{IPv4Subnet, IPv4Addr}
import org.slf4j.LoggerFactory

import org.midonet.cluster.client.BGPListBuilder
import org.midonet.cluster.data.{AdRoute, BGP}
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.topology.devices.RouterPort
import org.midonet.quagga.BgpdConfiguration.{Network, Neighbor, BgpRouter}

class BgpModelTranslator(var portId: UUID,
                         val config: MidolmanConfig,
                         val subscriber: (BgpRouter, Set[UUID]) => Unit) extends BGPListBuilder {

    val log = Logger(LoggerFactory.getLogger(s"org.midonet.routing.bgp-translator"))

    val NO_AS = -1

    var router: BgpRouter = BgpRouter(NO_AS)

    private var peersById: Map[UUID, BGP] = Map.empty
    private var peersByAddress: Map[IPv4Addr, BGP] = Map.empty
    private var localNetworks: Map[UUID, IPv4Subnet] = Map.empty

    private def publish(newRouter: BgpRouter): Unit = {
        if (newRouter != router) {
            router = newRouter
            subscriber(router, peersById.keys.toSet)
        }
    }

    override def addBGP(bgp: BGP): Unit = publish(_addBGP(bgp, router))

    private def updateAs(updateOn: BgpRouter, newAs: Int)(f: (BgpRouter) => BgpRouter): BgpRouter = {
        if (updateOn.as == NO_AS) {
            f(updateOn.copy(as = newAs))
        } else if (newAs != updateOn.as) {
            log.error(s"Inconsistent router configuration on port $portId, " +
                s"two different local AS numbers: $newAs / ${updateOn.as}, "+
                s"ignoring BGP session with AS $newAs")
            updateOn
        } else {
            f(updateOn)
        }
    }

    protected def _addBGP(bgp: BGP, _addTo: BgpRouter): BgpRouter = {
        var addTo = _addTo
        if (peersById.contains(bgp.getId))
            addTo = _removeBGP(bgp.getId, addTo)

        if (peersByAddress.contains(bgp.getPeerAddr)) {
            log.error(s"Ignoring BGP session, already have one for this remote address: ${bgp.getPeerAddr}")
            return addTo
        }

        updateAs(addTo, bgp.getLocalAS) {
            case r =>
                val neighbor = Neighbor(bgp.getPeerAddr,
                                        bgp.getPeerAS,
                                        Some(config.bgpKeepAlive),
                                        Some(config.bgpHoldTime),
                                        Some(config.bgpConnectRetry))

                peersById += bgp.getId -> bgp
                peersByAddress += bgp.getPeerAddr -> bgp
                r.copy(neighbors = addTo.neighbors + (bgp.getPeerAddr -> neighbor))
        }
    }

    override def updateBGP(bgp: BGP): Unit = publish(_updateBGP(bgp, router))

    protected def _updateBGP(bgp: BGP, _addTo: BgpRouter): BgpRouter = {
        _addBGP(bgp, _removeBGP(bgp.getId, _addTo))
    }

    override def removeBGP(bgpID: UUID): Unit = publish(_removeBGP(bgpID, router))

    protected def _removeBGP(bgpID: UUID, _removeFrom: BgpRouter): BgpRouter = {
        var removeFrom = _removeFrom

        if (peersById.contains(bgpID)) {
            val bgp = peersById(bgpID)
            removeFrom = removeFrom.copy(neighbors = removeFrom.neighbors - bgp.getPeerAddr)
            peersById -= bgpID
            peersByAddress -= bgp.getPeerAddr

            if (peersById.isEmpty)
                removeFrom = removeFrom.copy(as = NO_AS)
        }

        removeFrom
    }

    override def addAdvertisedRoute(route: AdRoute): Unit = {
        val cidr = IPv4Subnet.fromCidr(s"${route.getNwPrefix.getHostAddress}/${route.getPrefixLength}")
        localNetworks += route.getId -> cidr
        val net = Network(cidr)
        if (!router.networks.contains(net)) {
            publish(router.copy(networks = router.networks + net))
        }
    }

    override def removeAdvertisedRoute(route: AdRoute) {
        if (localNetworks.contains(route.getId)) {
            val cidr = IPv4Subnet.fromCidr(s"${route.getNwPrefix.getHostAddress}/${route.getPrefixLength}")

            localNetworks -= route.getId

            if (localNetworks.values.find(_ == cidr) eq None) {
                publish(router.copy(networks = router.networks - Network(cidr)))
            }
        }
    }
}
