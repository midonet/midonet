/**
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage

import org.midonet.cluster.models.Devices.Bridge
import org.midonet.cluster.models.Devices.Chain
import org.midonet.cluster.models.Devices.Port
import org.midonet.cluster.models.Devices.Router
import org.midonet.cluster.models.Devices.Rule
import org.midonet.cluster.util.UUID.randomUuidProto

/**
 * Provides utility methods for testing Storage Service.
 */
trait StorageServiceTester extends StorageService {
    /**
     * Cleans up all the data in the storage.
     */
    def cleanUpDirectories(): Unit

    /**
     * Cleans up the device data in the storage.
     */
    def cleanUpDeviceData(): Unit

    def createBridge(bridgeName: String) = {
        val bridge = Bridge.newBuilder
                           .setId(randomUuidProto)
                           .setName(bridgeName)
                           .build()
        create(bridge)
        bridge
    }

    def createRouter(routerName: String) = {
        val router = Router.newBuilder
                           .setId(randomUuidProto)
                           .setName(routerName)
                           .build()
        create(router)
        router
    }

    def createPort() = {
        val port = Port.newBuilder
                       .setId(randomUuidProto)
                       .build()
        create(port)
        port
    }

    def attachPortTo(router: Router) = {
        val routerPort = Port.newBuilder
                             .setId(randomUuidProto)
                             .setRouterId(router.getId)
                             .build()
        create(routerPort)
        routerPort
    }

    def attachPortTo(bridge: Bridge) = {
        val port = Port.newBuilder
                       .setId(randomUuidProto)
                       .setBridgeId(bridge.getId)
                       .build()
        create(port)
        port
    }

    def attachPortTo(bridge: Bridge, port: Port) = {
        val attachedPort = port.toBuilder
                               .setBridgeId(bridge.getId)
                               .build()
        update(port)
        attachedPort
    }

    def linkPorts(port: Port, peer: Port) {
        update(port.toBuilder
                   .setPeerUuid(peer.getId)
                   .build())
    }

    def connect(router1: Router, router2: Router) {
        val router1Port = attachPortTo(router1)
        val router2Port = attachPortTo(router2)
        linkPorts(router1Port, router2Port)
    }

    def connect(router: Router, bridge: Bridge) {
        val routerPort = attachPortTo(router)
        val bridgePort = attachPortTo(bridge)
        linkPorts(routerPort, bridgePort)
    }

    def createChain() = {
       val chain = Chain.newBuilder
                        .setId(randomUuidProto)
                        .build()
       create(chain)
       chain
    }

    def addRule(chain: Chain, action: Rule.Action) = {
       val rule = Rule.newBuilder
                      .setId(randomUuidProto)
                      .setAction(action)
                      .setChainId(chain.getId)
                      .build()
       create(rule)
       rule
    }

    def attachChains(bridge: Bridge, inbound: Chain, outbound: Chain) = {
        val bridgeWithChains = bridge.toBuilder
                                     .setInboundFilterId(inbound.getId)
                                     .setOutboundFilterId(outbound.getId)
                                     .build()
        update(bridgeWithChains)
        bridgeWithChains
    }
}