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

package org.midonet.brain.tools

import java.util.concurrent.{Executors, TimeUnit}

import scala.util.Random

import com.google.common.util.concurrent.AbstractService
import com.google.inject.Inject
import org.slf4j.LoggerFactory

import org.midonet.brain.BrainConfig
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology.{Network, Port, Router, Vtep}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.util.{IPAddressUtil, UUIDUtil}
import org.midonet.util.functors.makeRunnable

/**
 * Topology Zoom Updater service for testing topology components.
 * It creates a set of topology elements and interconnects them.
 * Please note that this is just for testing purposes and the data
 * in the objects and the connections between them may not be
 * consistent with an actual network architecture.
 */
class TopologyZoomUpdater @Inject()(val backend: MidonetBackend,
                                    val brainConf: BrainConfig)
    extends AbstractService {

    val conf = brainConf.topologyUpdater
    private val log = LoggerFactory.getLogger(classOf[TopologyZoomUpdater])
    private val pool = Executors.newScheduledThreadPool(conf.threads)

    private val storage = backend.store

    object Operation extends Enumeration {
        type Operation = Value
        val ADDITION, REMOVAL, UPDATE = Value
    }
    import Operation._

    // ProviderRouterPort, RouterPort, Router
    private type RouterInfo = (Port, Port, Router)
    // RouterId, RouterPort, BridgePort, Bridge
    private type NetworkInfo = (Commons.UUID, Port, Port, Network)
    // BridgeId, Port
    private type PortInfo = (Commons.UUID, Port)
    // Vtep
    private type VtepInfo = Vtep

    private val runnable: Runnable =
        makeRunnable({try {doSomething()} catch {
            case e: Throwable => log.error("failed scheduled execution", e)
        }})

    private val random = new Random()
    private var pRouter: Router = _
    private var routers: Map[Commons.UUID, RouterInfo] = Map()
    private var networks: Map[Commons.UUID, NetworkInfo] = Map()
    private var ports: Map[Commons.UUID, PortInfo] = Map()
    private var vteps: Map[Commons.UUID, VtepInfo] = Map()
    private var seq: Long = 0

    @Override
    override def doStart(): Unit = {
        log.info("Starting the Topology Zoom Updater")

        try {
            buildLayout()
            if (conf.period > 0)
                pool.scheduleAtFixedRate(runnable, conf.period, conf.period,
                                         TimeUnit.MILLISECONDS)
            log.info("Updater started")
            notifyStarted()
        } catch {
            case e: Exception =>
                log.warn("Updater failed to start")
                notifyFailed(e)
        }
    }

    @Override
    override def doStop(): Unit = {
        log.info("Stopping the Updater")
        pool.shutdown()
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow()
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.error("Unable to shutdown Updater thread pool")
                }
            }
        } catch {
            case e: InterruptedException =>
                log.warn("Interrupted while waiting for completion")
                pool.shutdownNow()
                Thread.currentThread().interrupt() // preserve status
        } finally {
            cleanUp()
        }
        log.info("Updater stopped")
        notifyStopped()
    }

    private def randomIp: String = {
        val (b1, b2, b3, b4) = (1 + random.nextInt(254),
                                1 + random.nextInt(254),
                                1 + random.nextInt(254),
                                1 + random.nextInt(254))
        s"$b1.$b2.$b3.$b4"
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

    private def createVtep(): Vtep = {
        val vtep = Vtep.newBuilder()
            .setId(UUIDUtil.randomUuidProto)
            .setManagementIp(IPAddressUtil.toProto(randomIp))
            .setManagementPort(6632)
            .build
        storage.create(vtep)
        vtep
    }

    private def linkPorts(p1: Port, p2: Port): (Port, Port) = {
        val updatedPort = p1.toBuilder.setPeerId(p2.getId).build()
        storage.update(updatedPort)
        (updatedPort, p2)
    }

    private def buildLayout() = {
        log.debug("building initial layout")
        pRouter = createRouter("pRouter")
        for (idx <- 0 to conf.initialRouters - 1) {
            addRouter()
        }
        for (idx <- 0 to conf.initialVteps - 1) {
            addVtep()
        }
    }

    private def cleanUp() = {
        routers.foreach(rt => {rmRouter(rt._2._3)})
        vteps.foreach(vt => {rmVtep(vt._2)})
        storage.delete(classOf[Router], pRouter.getId)
    }

    private def addPort(br: Network) = {
        val port = createPort(br)
        ports = ports + (port.getId -> (br.getId, port))
    }

    private def rmPort(p: Port) = {
        storage.delete(classOf[Port], p.getId)
        ports = ports - p.getId
    }

    private def updatePort(p: Port) = {
        val up: Boolean = p.hasAdminStateUp && p.getAdminStateUp
        val updated = p.toBuilder.setAdminStateUp(!up).build()
        val entry = ports(p.getId)
        val newEntry = (entry._1, updated)
        storage.update(updated)
        ports = ports.updated(updated.getId, newEntry)
    }

    private def addNetwork(rt: Router) = {
        seq += 1
        val br = createNetwork("b_" + rt.getName + "_" + seq)
        val (p1, p2) = linkPorts(createPort(br), createPort(rt))
        networks = networks + (br.getId -> (rt.getId, p1, p2, br))
        for (p <- 0 to conf.initialPortsPerNetwork - 1) {
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

    private def updateNetwork(br: Network) = {
        val up: Boolean = br.hasAdminStateUp && br.getAdminStateUp
        val updated = br.toBuilder.setAdminStateUp(!up).build()
        val entry = networks(br.getId)
        val newEntry = (entry._1, entry._2, entry._3, updated)
        storage.update(updated)
        networks = networks.updated(updated.getId, newEntry)
    }

    private def addRouter() = {
        seq += 1
        val rt = createRouter("r" + seq)
        val (p1, p2) = linkPorts(createPort(rt), createPort(pRouter))
        routers = routers + (rt.getId -> (p1, p2, rt))
        for (p <- 0 to conf.initialNetworksPerRouter) {
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

    private def updateRouter(rt: Router) = {
        val up: Boolean = rt.hasAdminStateUp && rt.getAdminStateUp
        val updated = rt.toBuilder.setAdminStateUp(!up).build()
        val entry = routers(rt.getId)
        val newEntry = (entry._1, entry._2, updated)
        storage.update(updated)
        routers = routers.updated(updated.getId, newEntry)
    }

    private def addVtep() = {
        val vtep = createVtep()
        vteps = vteps + (vtep.getId -> vtep)
    }

    private def rmVtep(vt: Vtep) = {
        vteps = vteps - vt.getId
        storage.delete(classOf[Vtep], vt.getId)
    }

    private def updateVtep(vt: Vtep) = {
        // do nothing
    }

    /* choose the next operation (removal, addition or update)
     * @param cur: is the current number of elements
     * @param initial: is the initial number of elements
     */
    private def chooseOperation(cur: Int, initial: Int): Operation = {
        random.nextInt(3) match {
            case 0 => UPDATE
            case 1 => if (cur <= (initial / 2)) UPDATE else REMOVAL
            case 2 => if (cur >= (initial + initial / 2)) UPDATE else ADDITION
        }
    }

    private def getRandomEntry[T](m: Map[_, T]): T = {
        m.toArray.apply(random.nextInt(m.size))._2
    }

    /* perform a random operation */
    private def doSomething() = {
        random.nextInt(4) match {
            case 0 =>
                log.debug("updating routers")
                val rt = getRandomEntry(routers)._3
                chooseOperation(routers.size, conf.initialRouters) match {
                    case UPDATE => updateRouter(rt)
                    case REMOVAL => rmRouter(rt)
                    case ADDITION => addRouter()
                }
            case 1 =>
                log.debug("updating networks")
                val rt = getRandomEntry(routers)._3
                val br = getRandomEntry(networks)._4
                chooseOperation(networks.size,
                                conf.initialRouters *
                                    conf.initialNetworksPerRouter) match {
                    case UPDATE => updateNetwork(br)
                    case REMOVAL => rmNetwork(br)
                    case ADDITION => addNetwork(rt)
                }
            case 2 =>
                log.debug("updating ports")
                val br = getRandomEntry(networks)._4
                val p = getRandomEntry(ports)._2
                chooseOperation(networks.size,
                                conf.initialRouters *
                                    conf.initialNetworksPerRouter *
                                    conf.initialPortsPerNetwork) match {
                    case UPDATE => updatePort(p)
                    case REMOVAL => rmPort(p)
                    case ADDITION => addPort(br)
                }
            case 3 =>
                log.debug("updating vteps")
                val vt = getRandomEntry(vteps)
                chooseOperation(vteps.size, conf.initialVteps) match {
                    case UPDATE => updateVtep(vt)
                    case REMOVAL => rmVtep(vt)
                    case ADDITION => addVtep()
                }
        }
    }
}
