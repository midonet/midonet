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

import java.util.UUID
import java.util.concurrent.TimeUnit

import scala.collection.{breakOut, mutable => m}

import com.google.common.util.concurrent.AbstractService

import rx.schedulers.Schedulers
import rx.{Observable, Observer, Subscription}

import org.midonet.midolman.NotYetException
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.midolman.host.services.QosService.TcConf
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.simulation.Port
import org.midonet.midolman.topology.devices.Host
import org.midonet.midolman.topology.{VirtualTopology, VirtualToPhysicalMapper => VTPM}
import org.midonet.util.concurrent.Executors
import org.midonet.util.functors.makeFunc1

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

    case class TcConf(ifindex: Int, rate: Int, burst: Int)
}

class QosService(scanner: InterfaceScanner,
                 hostId: java.util.UUID,
                 requestHandler: TcRequestHandler)
        extends AbstractService
        with MidolmanLogging {

    private val executor = Executors.singleThreadScheduledExecutor(
        "qos-service", isDaemon = true, Executors.CallerRunsPolicy)
    private val scheduler = Schedulers.from(executor)

    private val boundPortSubs = new m.HashMap[UUID, Subscription]()
    private var ifaceNameToId = new m.HashMap[String, Int]()
    private val portIdToPort = new m.HashMap[UUID, Port]()

    private var currentConfs = Set[TcConf]()

    private var hostsSubscription: Subscription = _

    private def generateRequests(): Unit = {

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

        val tcConfs: Set[TcConf] = portIdToPort.values
            .filter(hasLocalInterface)
            .filter(hasBandwidthRule)
            .map(portToQosConfig)(breakOut)

        val removeConfs = currentConfs diff tcConfs
        removeConfs.foreach { c =>
            log.debug(s"Removing TC configuration for interface ${c.ifindex}")
            requestHandler.delTcConfig(c.ifindex)
        }

        val addConfs = tcConfs diff currentConfs
        addConfs.foreach { c =>
            log.debug(s"Adding TC configuration for interface ${c.ifindex} " +
                     s"rate: ${c.rate} burst: ${c.burst}")
            requestHandler.addTcConfig(c.ifindex, c.rate, c.burst)
        }
        currentConfs = tcConfs
    }

    override def doStart() = {
        start()
        notifyStarted()
        log.info("QoS Service has started")
    }

    override def doStop(): Unit = {
        stop()
        notifyStopped()
        log.info("QoS Service has stopped")
    }

    protected def stop(): Unit = {
        hostsSubscription.unsubscribe()
        hostsSubscription = null

        Executors.shutdown(executor) { _ =>
            log warn s"Exception while stopping QoS Service executor"
        }
    }

    protected def start(): Unit = {
        scanner.subscribe(new Observer[Set[InterfaceDescription]] {
            override def onCompleted(): Unit = {
                //TODO: handle this error. This is not expected
                log.warn(s"Interface scanner for $hostId has stopped")
            }

            override def onError(t: Throwable): Unit = {
                //TODO: handle this error. This is not expected
                log.warn("Interface scanner error", t)
            }

            override def onNext(data: Set[InterfaceDescription]): Unit = {

                ifaceNameToId = data.map { d =>
                    (d.getName, d.getIfindex)}(breakOut)
                generateRequests()
            }
        }, Some(scheduler))

        var sleep = 1000

        val observer: Observer[Host] = new Observer[Host] {
            override def onCompleted(): Unit = {
                //TODO: This should never happen. How do we handle?
                log.warn(s"Host $hostId observable completed")
            }

            override def onError(e: Throwable) = {
                log.warn(s"Host $hostId error", e)
                throw e
            }

            override def onNext(h: Host): Unit = {
                log.debug(s"Host $hostId updated: $h")
                processHost(h)
            }
        }

        def retry(obs: Observable[_ <: Throwable]): Observable[_] = {
            /* Exponential back-off. It takes a few seconds for the host
             * to be registered. */
            obs.flatMap(makeFunc1[Throwable, Observable[java.lang.Long]]({
                case e: NotYetException =>
                    log.warn(s"Error subscribing to $hostId " +
                             s"notifications, retrying in $sleep milliseconds")
                    sleep = (sleep * 1.25).toInt
                    Observable.timer(sleep, TimeUnit.MILLISECONDS,
                                     scheduler)
                case e: Throwable =>
                    Observable.error(e)
            }))
        }

        hostsSubscription = VTPM.hosts(hostId)
                                .observeOn(scheduler)
                                .retryWhen(makeFunc1(retry))
                                .subscribe(observer)
    }

    private def stopFollowingPort(id: UUID): Unit = {
        boundPortSubs.remove(id)
        portIdToPort.remove(id)
        generateRequests()
    }

    private def subscribeToPort(id: UUID): Unit = {
        val pObs = new Observer[Port] {
            override def onCompleted(): Unit = {
                log.debug(s"Port $id deleted")
                stopFollowingPort(id)
            }
            override def onError(e: Throwable): Unit = {
                log.warn(s"Port $id error", e)
                stopFollowingPort(id)
            }
            override def onNext(p: Port): Unit = {
                log.debug(s"Port $id updated: $p")
                portIdToPort.put(p.id, p)
                generateRequests()
            }
        }
        val subscription = VirtualTopology.observable(classOf[Port], id)
                                          .observeOn(scheduler)
                                          .subscribe(pObs)
        boundPortSubs.put(id, subscription)
    }

    private def processHost(h: Host): Unit = {
        val bindings = h.portBindings.keySet

        bindings.filterNot(boundPortSubs.keySet)
                .foreach(subscribeToPort)

        boundPortSubs.keySet
            .filterNot(bindings)
            .foreach { id =>
                boundPortSubs(id).unsubscribe()
                stopFollowingPort(id)
            }

        generateRequests()
    }
}
