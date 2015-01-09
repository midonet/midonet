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

package org.midonet.brain.services.topology

import java.util.UUID

import com.google.inject.Inject
import org.midonet.brain.ClusterMinion
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology.{Network, Port, Router}
import org.midonet.cluster.util.UUIDUtil

import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage.Storage

/**
 * Topology tester service skeleton
 */
class TopologyTester @Inject()(val storage: Storage) extends ClusterMinion {
    private val log = LoggerFactory.getLogger(classOf[TopologyTester])

    import TopologyTester._

    // ProviderRouterPort, RouterPort, Router
    type RouterInfo = (Port, Port, Router)
    // RouterId, RouterPort, BridgePort, Bridge
    type NetworkInfo = (Commons.UUID, Port, Port, Network)
    // BridgeId, Port
    type PortInfo = (Commons.UUID, Port)

    var pRouter: Router = _
    var routers: Map[Commons.UUID, RouterInfo] = Map()
    var networks: Map[Commons.UUID, NetworkInfo] = Map()
    var ports: Map[Commons.UUID, PortInfo] = Map()

    @Override
    def doStart(): Unit = {
        log.info("Starting the Topology Tester Service")

        try {
            initializeStorage()
            buildLayout()
            log.info("Service started")
            notifyStarted()
        } catch {
            case e: Exception =>
                log.warn("Service start failed")
                notifyFailed(e)
        }
    }

    @Override
    def doStop(): Unit = {
        log.info("Stopping the Topology API Service")
        log.info("Service stopped")
        notifyStopped()
    }

    private def initializeStorage() = {
        storage.registerClass(classOf[Network])
        storage.registerClass(classOf[Router])
        storage.registerClass(classOf[Port])
        storage.build()
    }

    private def createRouter(name: String): Router = {
        val router = Router.newBuilder()
            .setId(UUIDUtil.randomUuidProto)
            .setName(name)
            .build
        storage.create(router)
        router
    }

    private def createNetwork(name: String): Network = {
        val network = Network.newBuilder()
            .setId(UUIDUtil.randomUuidProto)
            .setName(name)
            .build
        storage.create(network)
        network
    }

    private def createPort(router: Router): Port = {
        val port = Port.newBuilder()
            .setId(UUIDUtil.randomUuidProto)
            .setRouterId(router.getId)
            .build
        storage.create(port)
        port
    }

    private def createPort(network: Network): Port = {
        val port = Port.newBuilder()
            .setId(UUIDUtil.randomUuidProto)
            .setNetworkId(network.getId)
            .build
        storage.create(port)
        port
    }

    private def linkPorts(p1: Port, p2: Port): (Port, Port) = {
        val updatedPort = p1.toBuilder.setPeerId(p2.getId).build()
        storage.update(updatedPort)
        (updatedPort, p2)
    }

    private def buildLayout() = {
        pRouter = createRouter("pRouter")
        for (idx <- 0 to NROUTERS - 1) {
            val rt = createRouter("r" + (idx + 1))
            val (p1, p2) = linkPorts(createPort(rt), createPort(pRouter))
            routers = routers + (rt.getId -> (p1, p2, rt))
            for (n <- 0 to NBRIDGES - 1) {
                val br = createNetwork("b" + (idx + 1) + "_" + (n + 1))
                val (b1, b2) = linkPorts(createPort(br), createPort(rt))
                networks = networks + (br.getId -> (rt.getId, p1, p2, br))
                for (p <- 0 to NPORTS - 1) {
                    val port = createPort(br)
                    ports = ports + (port.getId -> (br.getId, port))
                }
            }
        }
    }
}

object TopologyTester {
    final val NROUTERS = 10
    final val NBRIDGES = 10
    final val NPORTS = 10
}
