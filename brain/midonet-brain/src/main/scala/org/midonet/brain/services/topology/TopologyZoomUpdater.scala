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

import scala.util.Random

import com.google.inject.Inject
import org.slf4j.LoggerFactory

import org.midonet.brain.{ClusterNode, ScheduledMinionConfig, ScheduledClusterMinion}
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology.{Network, Port, Router}
import org.midonet.cluster.util.UUIDUtil
import org.midonet.config._
import org.midonet.util.functors.makeRunnable

/**
 * Topology tester service skeleton
 */
class TopologyZoomUpdater @Inject()(val nodeContext: ClusterNode.Context,
                                    val storage: Storage,
                                    val cfg: TopologyZoomUpdaterConfig)
    extends ScheduledClusterMinion(nodeContext, cfg) {
    override protected val log = LoggerFactory.getLogger(classOf[TopologyZoomUpdater])

    // ProviderRouterPort, RouterPort, Router
    private type RouterInfo = (Port, Port, Router)
    // RouterId, RouterPort, BridgePort, Bridge
    private type NetworkInfo = (Commons.UUID, Port, Port, Network)
    // BridgeId, Port
    private type PortInfo = (Commons.UUID, Port)

    protected override val runnable: Runnable =
        makeRunnable({try {doSomething()} catch {
            case e: Throwable => log.error("failed scheduled execution", e)
        }})

    private val random = new Random()
    private var pRouter: Router = _
    private var routers: Map[Commons.UUID, RouterInfo] = Map()
    private var networks: Map[Commons.UUID, NetworkInfo] = Map()
    private var ports: Map[Commons.UUID, PortInfo] = Map()
    private var seq: Long = 0

    @Override
    override def doStart(): Unit = {
        log.info("Starting the Topology Tester Service")

        try {
            buildLayout()
            super.doStart()
            log.info("Service started")
        } catch {
            case e: Exception =>
                log.warn("Service start failed")
                notifyFailed(e)
        }
    }

    @Override
    override def doStop(): Unit = {
        log.info("Stopping the Topology API Service")
        try {
            super.doStop()
        } finally {
            cleanUp()
        }
        log.info("Service stopped")
        notifyStopped()
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
        log.debug("building initial layout")
        pRouter = createRouter("pRouter")
        for (idx <- 0 to cfg.initialRouters - 1) {
            addRouter()
        }
    }

    private def cleanUp() = {
        routers.foreach(rt => {rmRouter(rt._2._3)})
        storage.delete(classOf[Router], pRouter)
    }

    private def addPort(br: Network) = {
        val port = createPort(br)
        ports = ports + (port.getId -> (br.getId, port))
    }

    private def rmPort(p: Port) = {
        storage.delete(classOf[Port], p.getId)
        ports = ports - p.getId
    }

    private def addNetwork(rt: Router) = {
        seq += 1
        val br = createNetwork("b_" + rt.getName + "_" + seq)
        val (p1, p2) = linkPorts(createPort(br), createPort(rt))
        networks = networks + (br.getId -> (rt.getId, p1, p2, br))
        for (p <- 0 to cfg.initialPortsPerNetwork - 1) {
            addPort(br)
        }
    }

    private def rmNetwork(br: Network) = {
        ports.filter({_._2._1 == br.getId}).map({_._2._2}).foreach({rmPort})
        val info: NetworkInfo = networks(br.getId)
        storage.delete(classOf[Port], info._2.getId)
        storage.delete(classOf[Port], info._3.getId)
        storage.delete(classOf[Network], br.getId)
        networks = networks - br.getId
    }

    private def addRouter() = {
        seq += 1
        val rt = createRouter("r" + seq)
        val (p1, p2) = linkPorts(createPort(rt), createPort(pRouter))
        routers = routers + (rt.getId -> (p1, p2, rt))
        for (p <- 0 to cfg.initialNetworksPerRouter) {
            addNetwork(rt)
        }
    }

    private def rmRouter(rt: Router) = {
        networks.filter({_._2._1 == rt.getId}).map({_._2._4}).foreach({rmNetwork})
        val info: RouterInfo = routers(rt.getId)
        storage.delete(classOf[Port], info._1.getId)
        storage.delete(classOf[Port], info._2.getId)
        storage.delete(classOf[Router], rt.getId)
        routers = routers - rt.getId
    }

    /* choose the next operation (removal or addition)
     * @param cur: is the current number of elements
     * @param initial: is the initial number of elements
     */
    private def chooseRemoval(cur: Int, initial: Int): Boolean = {
        if (cur <= (initial / 2)) false
        else if (cur >= (initial + initial / 2)) true
        else random.nextBoolean()
    }

    private def getRandomEntry[T](m: Map[_, T]): T = {
        m.toArray.apply(random.nextInt(m.size))._2
    }

    /* perform a random operation */
    private def doSomething() = {
        random.nextInt(3) match {
            case 0 =>
                log.debug("updating routers")
                if (chooseRemoval(routers.size, cfg.initialRouters))
                    rmRouter(getRandomEntry(routers)._3)
                else
                    addRouter()
            case 1 =>
                log.debug("updating networks")
                if (chooseRemoval(networks.size,
                                  cfg.initialRouters *
                                  cfg.initialNetworksPerRouter))
                    rmNetwork(getRandomEntry(networks)._4)
                else
                    addNetwork(getRandomEntry(routers)._3)
            case 2 =>
                log.debug("updating ports")
                if (chooseRemoval(ports.size, cfg.initialRouters *
                                              cfg.initialNetworksPerRouter *
                                              cfg.initialPortsPerNetwork))
                    rmPort(getRandomEntry(ports)._2)
                else
                    addPort(getRandomEntry(networks)._4)
        }
    }
}

/** Configuration for the Topology Tester */
@ConfigGroup("topology_zoom_updater")
trait TopologyZoomUpdaterConfig extends ScheduledMinionConfig[TopologyZoomUpdater] {
    import TopologyZoomUpdaterConfig._
    @ConfigBool(key = "enabled", defaultValue = false)
    override def isEnabled: Boolean

    @ConfigString(key = "with",
                  defaultValue =
                      "org.midonet.brain.services.topology.TopologyZoomUpdater")
    override def minionClass: String

    @ConfigInt(key = "num_threads", defaultValue = DEFAULT_NUMTHREADS)
    override def numThreads: Int

    @ConfigLong(key = "delay_ms", defaultValue = DEFAULT_DELAY)
    override def delayMs: Long

    @ConfigLong(key = "period_ms", defaultValue = DEFAULT_INTERVAL)
    override def periodMs: Long

    @ConfigInt(key = "initial_routers", defaultValue = NROUTERS)
    def initialRouters: Int

    @ConfigInt(key = "initial_networks_per_router", defaultValue = NBRIDGES)
    def initialNetworksPerRouter: Int

    @ConfigInt(key = "initial_ports_per_network", defaultValue = NPORTS)
    def initialPortsPerNetwork: Int
}

object TopologyZoomUpdaterConfig {
    final val NROUTERS = 10
    final val NBRIDGES = 10
    final val NPORTS = 10
    final val DEFAULT_NUMTHREADS = 1
    final val DEFAULT_DELAY = 2000
    final val DEFAULT_INTERVAL = 2000
}
