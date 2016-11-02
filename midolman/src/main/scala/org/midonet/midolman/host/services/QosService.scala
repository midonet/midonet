/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.midolman.host.services

import java.util.concurrent.{Executors, TimeUnit}
import java.util.UUID

import scala.collection.{breakOut, mutable => m}

import com.google.common.util.concurrent.AbstractService

import rx.schedulers.Schedulers
import rx.{Observer, Subscription}

import org.midonet.cluster.data.storage.NotFoundException
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.simulation.Port
import org.midonet.midolman.topology.devices.Host
import org.midonet.midolman.topology.{VirtualTopology, VirtualToPhysicalMapper => VTPM}

/*
 * This is a service that runs on each midolman host and listens to
 * configuration changes to ports regarding QoS policies. It uses the
 * InterfaceScanner to get information about the interfaces that currently
 * exist on the underlying host, and uses the VirtualTopology to get the
 * QosPolicy information on the midonet ports. When there is both a physical
 * interface and a midonet port bound to that interface, it will pass off a TC
 * configuration request to the kernel.
 */
object QosService {
    def apply(scanner: InterfaceScanner,
              hostId: java.util.UUID,
              reqHandler: TcRequestHandler): QosService = {
        new QosService(scanner, hostId, reqHandler)

    }
}

case class TcConf(ifindex: Int, rate: Int, burst: Int)

class QosService(scanner: InterfaceScanner,
                 hostId: java.util.UUID,
                 requestHandler: TcRequestHandler)
        extends AbstractService
        with MidolmanLogging {

    val executor = Executors.newSingleThreadExecutor()
    val scheduler = Schedulers.from(executor)

    val boundPortSubs = new m.HashMap[UUID, Subscription]()
    var ifaceNameToId = new m.HashMap[String, Int]()
    val portIdToPort = new m.HashMap[UUID, Port]()
    val currentConfs = new m.HashSet[TcConf]()

    def generateRequests(): Unit = {

        /*
         * These functions are nested because they should only be called
         * from within this function, to preserve thread safety
         */
        def hasLocalInterface(p: Port): Boolean = {
            ifaceNameToId.contains(p.interfaceName)
        }

        def hasBandwidthRule(p: Port): Boolean = {
            p.qosPolicy != null &&
            p.qosPolicy.bandwidthRules != null &&
            p.qosPolicy.bandwidthRules.nonEmpty
        }

        def portToQosConfig(p: Port): TcConf = {
            val ifindex = ifaceNameToId(p.interfaceName)
            /*
             * TODO: in the future, the rules may have more than one element
             * in the list of rules. Each of these rules will have to result
             * in a new configuration.
             */
            val r = p.qosPolicy.bandwidthRules.head
            TcConf(ifindex, r.maxKbps, r.maxBurstKbps)
        }

        def removeRequest(c: TcConf): Unit = {
            log.info(s"Removing TC configuration for interface ${c.ifindex}")
            requestHandler.delTcConfig(c.ifindex)
            currentConfs.remove(c)
        }

        def addRequest(c: TcConf): Unit = {
            log.info(s"adding TC configuration for interface ${c.ifindex} " +
                     s"rate: ${c.rate}, burst: ${c.burst}")
            requestHandler.addTcConfig(c.ifindex, c.rate, c.burst)
            currentConfs.add(TcConf(c.ifindex, c.rate, c.burst))
        }

        val tcConfs = portIdToPort.values
            .filter(hasLocalInterface)
            .filter(hasBandwidthRule)
            .map(portToQosConfig).toSet

        currentConfs.filterNot(tcConfs)
            .foreach(removeRequest)

        tcConfs.filterNot(currentConfs)
            .foreach(addRequest)
    }

    override def doStop(): Unit = {
        notifyStopped()
        log.info("QoS Service has stopped")
    }

    override def doStart() = {
        start()
        notifyStarted()
        log.info("QoS Service has started")
    }

    def start(): Unit = {
        scanner.subscribe(new Observer[Set[InterfaceDescription]] {
            override def onCompleted(): Unit = {
                // Expected when Midolman shuts down, but otherwise indicates
                // an error.
                log.info(s"InterfaceScanner completed.")
            }

            override def onError(t: Throwable): Unit = {
                log.error(s"InterfaceScanner raised error: ${t.getMessage}", t)
            }

            override def onNext(data: Set[InterfaceDescription]): Unit = {
                ifaceNameToId = data.map { d =>
                    (d.getName, d.getIfindex)}(breakOut)
                generateRequests()
            }
        }, Some(scheduler))

        val observer: Observer[Host] = new Observer[Host] {
            // Retry with exponential backoff.
            var sleep = 1000

            override def onCompleted(): Unit = {
                // Host was deleted, possibly because of loss of connection to
                // Zookeeper. Retry.
                log.error(s"Host $hostId observer completed, possibly due to " +
                          "loss of connection to Zookeeper. Attempting to " +
                          "resubscribe.")
                retrySubscribe()
            }

            override def onError(e: Throwable) = e match {
                /*
                 * Exponential back-off. It takes a few seconds for the host
                 * to be registered.
                 */
                case e: NotFoundException =>
                    log.warn(s"Error subscribing to $hostId " +
                             s"notifications. Retrying in $sleep ms.")
                    retrySubscribe()
                case _ =>
                    log.error(s"Error subscribing to host $hostId")
                    throw e
            }

            override def onNext(h: Host): Unit = {
                log.debug(s"received update for host: $h")
                sleep = 1000 // Reset exponential backoff.
                processHost(h)
            }

            private def retrySubscribe(): Unit = {
                VTPM.hosts(hostId)
                    .delaySubscription(sleep, TimeUnit.MILLISECONDS)
                    .observeOn(scheduler)
                    .subscribe(this)
                sleep = (sleep * 1.25).toInt
            }
        }

        VTPM.hosts(hostId).observeOn(scheduler).subscribe(observer)
    }

    def stopFollowingPort(id: UUID): Unit = {
        boundPortSubs.remove(id)
        portIdToPort.remove(id)
        generateRequests()
    }

    def subscribeToPort(id: UUID): Unit = {
        val pObs = new Observer[Port] {
            override def onCompleted(): Unit = {
                log.info(s"received removal request for port: $id")
                stopFollowingPort(id)
            }
            override def onError(e: Throwable): Unit = {
                log.error(s"port observer for $id has thrown an error: " +
                          s"${e.getMessage}, ${e.getStackTrace}")
                stopFollowingPort(id)
            }
            override def onNext(p: Port): Unit = {
                log.info(s"received update to port: $p")
                portIdToPort.put(p.id, p)
                generateRequests()
            }
        }
        val subscription = VirtualTopology.observable(classOf[Port], id)
                                          .observeOn(scheduler)
                                          .subscribe(pObs)
        boundPortSubs.put(id, subscription)
    }

    def processHost(h: Host): Unit = {
        val bindings = h.portBindings.keySet

        bindings.filterNot(boundPortSubs.keySet)
                .foreach(subscribeToPort)

        boundPortSubs.keySet
            .filterNot(bindings)
            .foreach{ id =>
                boundPortSubs(id).unsubscribe()
                stopFollowingPort(id)
            }

        generateRequests()
    }
}
