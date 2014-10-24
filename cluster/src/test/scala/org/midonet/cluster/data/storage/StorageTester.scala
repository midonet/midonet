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
package org.midonet.cluster.data.storage

import java.util

import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer

import com.google.common.collect.{ArrayListMultimap, Multimap, Multimaps}

import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Devices.{Chain, Network, Port, Router, Rule}
import org.midonet.cluster.util.UUIDUtil.randomUuidProto

/**
 * Provides utility methods for testing Storage Service.
 */
trait StorageTester extends Storage {
    import org.midonet.cluster.data.storage.StorageTester._

    private[storage] type Devices = Multimap[Class[_], Commons.UUID]

    private def deviceMultimap =
        ArrayListMultimap.create[Class[_], Commons.UUID]()

    private[storage]
    def emptyDeviceCollection: Devices =
        Multimaps.synchronizedListMultimap(deviceMultimap)

    private[storage]
    def splitDeviceCollection(devices: Devices, splits: Int) = {
        val deviceCollections = new util.ArrayList[Devices](splits)
        for (i <- 1 to splits) deviceCollections.add(deviceMultimap)

        var index: Long = 0
        for (device <- devices.entries) {
            deviceCollections(index.toInt % splits).put(device.getKey,
                                                        device.getValue)
            index += 1
        }

        deviceCollections
    }

    /**
     * Cleans up all the data in the storage.
     */
    def cleanUpDirectories(): Unit

    /**
     * Cleans up the device data in the storage.
     */
    def cleanUpDeviceData(): Unit

    /**
     * Creates a network with a given name.
     */
    def createNetwork(networkName: String): Network = {
        createNetwork(networkName, null)
    }

    /**
     * Creates a network with a given name and add the device ID to devices.
     */
    def createNetwork(networkName: String, devices: Devices) = {
        val network = Network.newBuilder
                             .setId(randomUuidProto)
                             .setName(networkName)
                             .build()
        create(network)
        if (devices != null) devices.put(classOf[Network], network.getId)
        network
    }

    /**
     * Creates a router with a given name.
     */
    def createRouter(routerName: String): Router = {
        createRouter(routerName, null)
    }

    /**
     * Creates a router with a given name and add the device ID to devices.
     */
    def createRouter(routerName: String, devices: Devices) = {
        val router = Router.newBuilder
                           .setId(randomUuidProto)
                           .setName(routerName)
                           .build()
        create(router)
        if (devices != null) devices.put(classOf[Router], router.getId)
        router
    }

    /**
     * Creates a new port.
     */
    def createPort(): Port = {
        createPort(null, null)
    }

    def createPort(ops: ListBuffer[PersistenceOp], devices: Devices) = {
        val port = ProtoPort
        if (ops != null) ops += CreateOp(port)
        else create(port)
        if (devices != null) devices.put(classOf[Port], port.getId)
        port
    }

    /**
     * Creates a new port that's attached to the router.
     */
    def attachPortTo(router: Router) = {
        val port = ProtoPort(router)
        create(port)
        port
    }

    /**
     * Creates a new port that's attached to the network.
     */
    def attachPortTo(network: Network): Port = {
        attachPortTo(network, null, null)
    }

    /**
     * Creates a new port that's attached to the network. If "ops" is given,
     * a CreateOp is created and stored in ops. Otherwise the port is
     * created in normal operation.
     */
    def attachPortTo(network: Network,
                     ops: ListBuffer[PersistenceOp],
                     devices: Devices): Port = {
        val port = ProtoPort(network)
        if (ops != null) ops += CreateOp(port)
        else create(port)
        if (devices != null) devices.put(classOf[Port], port.getId)
        port
    }

    /**
     * Attaches the given port to the given network.
     */
    def attachPortTo(network: Network, port: Port) = {
        val attachedPort = port.toBuilder
                               .setNetworkId(network.getId)
                               .build()
        update(port)
        attachedPort
    }

    /**
     * Links ports to each other.
     */
    def linkPorts(port: Port, peer: Port) {
        update(port.toBuilder
                   .setPeerId(peer.getId)
                   .build())
    }

    /**
     * Connects the given routers by creating internal ports for each and
     * linking them.
     */
    def connect(router1: Router, router2: Router) {
        val router1Port = attachPortTo(router1)
        val router2Port = attachPortTo(router2)
        linkPorts(router1Port, router2Port)
    }

    /**
     * Connects the given router and network by creating internal ports for each
     * and linking them.
     */
    def connect(router: Router, network: Network) {
        val routerPort = attachPortTo(router)
        val networkPort = attachPortTo(network)
        linkPorts(routerPort, networkPort)
    }

    /**
     * Creates a new chain. If "ops" is given, a CreateOP is created and stored
     * in ops. Otherwise the port is created in normal operation.
     */
    def createChain(ops: ListBuffer[PersistenceOp], devices: Devices) = {
       val chain = ProtoChain()
       if (ops != null) ops += CreateOp(chain)
       else create(chain)
       if (devices != null) devices.put(classOf[Chain], chain.getId)
       chain
    }

    /**
     * Adds a new rule to the given chain. If "ops" is given, a CreateOP is
     * created and stored in ops. Otherwise the rule is created in normal
     * operation.
     */
    def addRule(chain: Chain,
                action: Rule.Action,
                ops: ListBuffer[PersistenceOp],
                devices: Devices) = {
       val rule = ProtoRule(action, chain.getId)
       if (ops != null) ops += CreateOp(rule)
       else create(rule)
       if (devices != null) devices.put(classOf[Rule], rule.getId)
       rule
    }

    /**
     * Attaches the given in/out-bound chains to the network. If "ops" is given,
     * an UpdateOP is created and stored in ops. Otherwise the network is
     * updated in a normal operation.
     */
    def attachChains(network: Network,
                     inbound: Chain,
                     outbound: Chain,
                     ops: ListBuffer[PersistenceOp]) = {
        val networkWithChains = network.toBuilder
                                       .setInboundFilterId(inbound.getId)
                                       .setOutboundFilterId(outbound.getId)
                                       .build()
        if (ops != null) ops += UpdateOp(networkWithChains)
        else update(networkWithChains)
        networkWithChains
    }
}

private object StorageTester {
    def ProtoPort(): Port = {
        ProtoPort(null, null)
    }

    def ProtoPort(network: Network): Port = {
        ProtoPort(network.getId, null)
    }

    def ProtoPort(router: Router): Port = {
        ProtoPort(null, router.getId)
    }

    def ProtoPort(networkId: Commons.UUID, routerId: Commons.UUID): Port = {
        val portBuilder = Port.newBuilder.setId(randomUuidProto)
        if (networkId != null) portBuilder.setNetworkId(networkId)
        if (routerId != null) portBuilder.setRouterId(routerId)

        portBuilder.build
    }

    def ProtoChain(): Chain =
       Chain.newBuilder.setId(randomUuidProto).build()

    def ProtoRule(action: Rule.Action, chainId: Commons.UUID): Rule = {
        val ruleBuilder = Rule.newBuilder.setId(randomUuidProto)
        if (action != null) ruleBuilder.setAction(action)
        if (chainId != null) ruleBuilder.setChainId(chainId)

        ruleBuilder.build()
    }
}
