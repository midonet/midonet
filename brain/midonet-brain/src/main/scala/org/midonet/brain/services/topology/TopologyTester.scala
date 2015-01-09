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

import java.util.concurrent.{ScheduledFuture, TimeUnit, Executors}

import scala.util.Random

import com.google.inject.Inject
import org.slf4j.LoggerFactory

import org.midonet.brain.ClusterMinion
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology.{Network, Port, Router}
import org.midonet.cluster.util.UUIDUtil
import org.midonet.util.functors.makeRunnable

/**
 * Topology tester service skeleton
 */
class TopologyTester @Inject()(val storage: Storage) extends ClusterMinion {
    private val log = LoggerFactory.getLogger(classOf[TopologyTester])

    import TopologyTester._

    // ProviderRouterPort, RouterPort, Router
    private type RouterInfo = (Port, Port, Router)
    // RouterId, RouterPort, BridgePort, Bridge
    private type NetworkInfo = (Commons.UUID, Port, Port, Network)
    // BridgeId, Port
    private type PortInfo = (Commons.UUID, Port)

    private val pool = Executors.newScheduledThreadPool(1)
    private val runnable: Runnable =
        makeRunnable({try {doSomething()} catch {
            case e: Throwable => log.error("failed scheduled execution", e)
        }})

    private var pRouter: Router = _
    private var routers: Map[Commons.UUID, RouterInfo] = Map()
    private var networks: Map[Commons.UUID, NetworkInfo] = Map()
    private var ports: Map[Commons.UUID, PortInfo] = Map()
    private var task: ScheduledFuture[_] = null
    private var seq: Long = 0

    @Override
    def doStart(): Unit = {
        log.info("Starting the Topology Tester Service")

        try {
            buildLayout()
            task = pool.scheduleAtFixedRate(runnable, INTERVAL, INTERVAL,
                                     TimeUnit.MILLISECONDS)
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
        try {
            if (task != null)
                task.cancel(true)
            pool.shutdown()
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow()
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.error("Unable to shut down service thread pool")
                }
            }
        } catch {
            case e: InterruptedException =>
                log.warn("Interrupted while waiting for completion")
                pool.shutdownNow()
                Thread.currentThread().interrupt()
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
        for (idx <- 0 to NROUTERS - 1) {
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
        for (p <- 0 to NPORTS - 1) {
            addPort(br)
        }
    }

    private def rmNetwork(br: Network) = {
        ports.filter({_._2._1 == br.getId}).map({_._2._2}).foreach({rmPort(_)})
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
        for (p <- 0 to NBRIDGES) {
            addNetwork(rt)
        }
    }

    private def rmRouter(rt: Router) = {
        networks.filter({_._2._1 == rt.getId}).map({_._2._4}).foreach({rmNetwork(_)})
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
                if (chooseRemoval(routers.size, NROUTERS))
                    rmRouter(getRandomEntry(routers)._3)
                else
                    addRouter()
            case 1 =>
                log.debug("updating networks")
                if (chooseRemoval(networks.size, NROUTERS * NBRIDGES))
                    rmNetwork(getRandomEntry(networks)._4)
                else
                    addNetwork(getRandomEntry(routers)._3)
            case 2 =>
                log.debug("updating ports")
                if (chooseRemoval(ports.size, NROUTERS * NBRIDGES * NPORTS))
                    rmPort(getRandomEntry(ports)._2)
                else
                    addPort(getRandomEntry(networks)._4)
        }
    }
}

object TopologyTester {
    final val NROUTERS = 10
    final val NBRIDGES = 10
    final val NPORTS = 10
    final val INTERVAL = 2000
    final val random = new Random()
}
