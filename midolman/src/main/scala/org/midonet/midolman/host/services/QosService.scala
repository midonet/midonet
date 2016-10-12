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

import com.google.common.util.concurrent.AbstractService
import java.util.concurrent.{LinkedBlockingQueue => LBQ, TimeUnit}
import java.util.UUID

import rx.{Observer, Subscription}
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.simulation.Port
import org.midonet.midolman.topology.devices.{Host, PortBinding}
import org.midonet.midolman.topology.{VirtualTopology, VirtualToPhysicalMapper => VTPM}
import org.midonet.netlink._

import scala.collection.mutable.HashMap
import scala.collection.mutable.HashSet

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
    def makeQosService(channelFactory: NetlinkChannelFactory,
                       scanner: InterfaceScanner,
                       hostId: java.util.UUID,
                       q: LBQ[TcRequest],
                       reqHandler: TcRequestHandler): QosService = {
        new QosService(channelFactory, scanner, hostId, q, reqHandler)

    }

    def makeQosService(channelFactory: NetlinkChannelFactory,
                       scanner: InterfaceScanner,
                       hostId: java.util.UUID): QosService = {
        val q = new LBQ[TcRequest]()
        val requestHandler = new TcRequestHandler(channelFactory, q)
        makeQosService(channelFactory, scanner, hostId, q, requestHandler)
    }
}

case class TcConf(ifindex: Int, rate: Int, burst: Int)

class QosService(channelFactory: NetlinkChannelFactory,
                 scanner: InterfaceScanner,
                 hostId: java.util.UUID,
                 q: LBQ[TcRequest],
                 requestHandler: TcRequestHandler)
        extends AbstractService
        with MidolmanLogging {

    val boundPortsObvrs = new HashMap[UUID, Subscription]()
    var ifaceNameToId = new HashMap[String, Int]()
    val portIdToPort = new HashMap[UUID, Port]()
    var currentConfs = new HashSet[TcConf]()

    def generateRequests(): Unit = {

        def hasBandwidthRule(p: Port): Boolean = {
            ifaceNameToId.contains(p.interfaceName) &&
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
            val qbw = p.qosPolicy.bandwidthRules.head.maxKbps
            val qb = p.qosPolicy.bandwidthRules.head.maxBurstKbps
            new TcConf(ifindex, qbw, qb)
        }

        val tcConfs = portIdToPort.map { case (id, port) => port }
            .filter(hasBandwidthRule)
            .map(portToQosConfig).toList

        def removeRequest(c: TcConf): Unit = {
            log.info(s"Removing TC configuration for interface ${c.ifindex}")
            q.add(new TcRequest(TcRequestOps.REMQDISC, c.ifindex))
            currentConfs.remove(c)
        }

        def addRequest(c: TcConf): Unit = {
            log.info(s"adding TC configuration for interface ${c.ifindex} " +
                     s"rate: ${c.rate}, burst: ${c.burst}")
            q.add(new TcRequest(TcRequestOps.ADDFILTER, c.ifindex, c.rate,
                  c.burst))
            currentConfs.add(new TcConf(c.ifindex, c.rate, c.burst))
        }

        currentConfs.filterNot(tcConfs contains _)
                    .foreach (removeRequest)

        tcConfs.filterNot(currentConfs.contains(_))
               .foreach(addRequest)
    }

    override def doStop(): Unit = {
        requestHandler.stop()
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
                //TODO: handle this error. This is not expected
                log.error(s"InterfaceScanner for $hostId has stopped")
            }

            override def onError(t: Throwable): Unit = {
                //TODO: handle this error. This is not expected
                log.error(s"InterfaceScanner has thrown an error: " +
                          s"${t.getMessage}, ${t.getStackTrace}")
            }

            override def onNext(data: Set[InterfaceDescription]): Unit = {
                data.foreach(ifd => ifaceNameToId.put(ifd.getName,
                                                      ifd.getIfindex))

                val dataIfaceNames = data.toList.map(_.getName)

                ifaceNameToId.keySet
                             .filterNot(dataIfaceNames contains _)
                             .foreach(ifaceNameToId remove _)

                data.filterNot(ifd => ifaceNameToId.contains(ifd.getName))
                    .foreach(ifd => ifaceNameToId.remove(ifd.getName))

                generateRequests()
            }
        })

        var sleep = 1000

        val observer: Observer[Host] = new Observer[Host] {
            override def onCompleted(): Unit = {
                //TODO: This should never happen. How do we handle?
                log.error(s"Host $hostId observer completed.")
            }

            override def onError(e: Throwable): Unit = {
                /*
                 * Exponential back-off. It takes a few seconds for the host
                 * to be registered.
                 */
                sleep = (sleep * 1.25).toInt
                log.error(s"Error subscribing to $hostId notifications. " +
                          s" retrying in $sleep ms.")
                VTPM.hosts(hostId)
                    .delaySubscription(sleep, TimeUnit.MILLISECONDS)
                    .subscribe(this)
            }

            override def onNext(h: Host): Unit = {
                log.debug(s"received update for host: $h")
                processHost(h)
            }
        }

        requestHandler.start()
        VTPM.hosts(hostId).subscribe(observer)
    }

    def stopFollowingPort(id: UUID): Unit = {
        boundPortsObvrs.remove(id)
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
                log.error(s"InterfaceScanner has thrown an error: " +
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
                                          .subscribe(pObs)
        boundPortsObvrs.put(id, subscription)
    }

    def processHost(h: Host): Unit = {
        val bindings = h.portBindings
          .map { case (id, PortBinding(name, _, _, _)) => id }.toSet

        bindings.filterNot { boundPortsObvrs.contains }
                .foreach { subscribeToPort }

        boundPortsObvrs.keySet.filterNot {bindings contains }
                       .foreach{ id =>
                           boundPortsObvrs(id).unsubscribe()
                           stopFollowingPort(id) }
    }
}
