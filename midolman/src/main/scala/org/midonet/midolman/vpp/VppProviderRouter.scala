/*
 * Copyright 2017 Midokura SARL
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

package org.midonet.midolman.vpp

import java.util
import java.util.{Collections, UUID, List => JList, Map => JMap}

import javax.annotation.concurrent.NotThreadSafe

import scala.collection.JavaConverters._

import rx.Observable.OnSubscribe
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject
import rx.{Observable, Observer, Subscriber}

import org.midonet.cluster.models.Topology
import org.midonet.cluster.models.Topology.Router
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.simulation.Port
import org.midonet.midolman.topology.{ObjectReferenceTracker, StoreObjectReferenceTracker, VirtualTopology}
import org.midonet.midolman.vpp.VppFip64.Notification
import org.midonet.midolman.vpp.VppProviderRouter.{Gateways, ProviderRouter, RouterState, UplinkState}
import org.midonet.util.functors.{makeAction0, makeAction1, makeFunc1, makeRunnable}
import org.midonet.util.logging.Logger

object VppProviderRouter {

    /**
      * A [[Notification]] with the active gateways for the given uplink port.
      * The gateways are the hosts with active ports that are in the same
      * stateful port group as the given uplink port. The list of hosts may
      * include the current host.
      */
    case class Gateways(portId: UUID, hostIds: Set[UUID]) extends Notification

    /**
      * Indicates the interior provider router ports and their corresponding
      * peer ports for a provider router configured for a specific uplink.
      */
    case class ProviderRouter(routerId: UUID, ports: JMap[UUID, UUID])

    /**
      * Maintains the state for a given uplink port, and exposes an observable
      * that emits notifications with the gateway hosts that correspond to the
      * uplink's port group.
      */
    private class UplinkState(val portId: UUID,
                              val routerId: UUID,
                              val groupPortIds: JList[UUID],
                              vt: VirtualTopology,
                              log: Logger) {

        private val mark = PublishSubject.create[Set[UUID]]()
        private val scheduler = Schedulers.from(vt.vtExecutor)

        /**
          * An [[Observable]] that emits notifications with the gateway hosts
          * corresponding to the active ports from the same stateful port
          * group(s) as the current uplink port. The list may include the
          * current host.
          */
        val observable = Observable.create[Set[UUID]](new OnSubscribe[Set[UUID]] {
            override def call(child: Subscriber[_ >: Set[UUID]]): Unit = {
                vt.vtExecutor.execute(makeRunnable {
                    // The subscription is performed on the VT thread since
                    // the tracker is not thread-safe.
                    log debug s"Subscribing to uplink port $portId for provider " +
                              s"router $routerId with stateful group ports " +
                              s"$groupPortIds"
                    val tracker =
                        new ObjectReferenceTracker[Port](vt, classOf[Port], log)
                    tracker.refsObservable
                        .filter(makeFunc1(isReady(_, tracker)))
                        .map[Set[UUID]](makeFunc1(toGateways(_, tracker)))
                        .distinctUntilChanged()
                        .takeUntil(mark)
                        .subscribe(child)
                    tracker.requestRefs(groupPortIds.asScala.toSet)
                })
            }
        })

        /**
          * Completes the state for the current uplink state by completing the
          * notification observable.
          */
        def complete(): Unit = {
            mark.onCompleted()
        }

        /**
          * @return True if all ports from the port group have been loaded.
          */
        private def isReady(port: Port, tracker: ObjectReferenceTracker[Port])
        : Boolean = {
            val ready = tracker.areRefsReady
            log debug s"Port group ports $groupPortIds ready: $ready"
            ready
        }

        /**
          * Maps the current list of port group ports to the corresponding
          * gateway hosts.
          */
        private def toGateways(port: Port, tracker: ObjectReferenceTracker[Port])
        : Set[UUID] = {
            val hostIds = for ((portId, port) <- tracker.currentRefs
                 if port.isActive && (port.hostId ne null)) yield {
               port.hostId
            }
            log debug s"Gateways for uplink port $portId: $hostIds"
            hostIds.toSet
        }
    }

    /**
      * Maintains the state for a provider router, and exposes an observable
      * that emits notifications with the router's interior ports and their
      * corresponding peer ports.
      */
    private class RouterState(val routerId: UUID, hostId: UUID,
                              vt: VirtualTopology, log: Logger) {

        final val defaultRouter = ProviderRouter(routerId, Collections.emptyMap())

        private var currentRouter: Router = _

        private val mark = PublishSubject.create[ProviderRouter]()
        private val scheduler = Schedulers.from(vt.vtExecutor)

        private val portTracker = new StoreObjectReferenceTracker[Topology.Port](
            vt, classOf[Topology.Port], log)

        private val routerObservable = vt.store
            .observable(classOf[Router], routerId)
            .observeOn(scheduler)
            .doOnNext(makeAction1(routerUpdated))
            .doOnCompleted(makeAction0(routerDeleted()))

        private val providerRouterObservable = Observable
            .merge(portTracker.refsObservable, routerObservable)
            .filter(makeFunc1(isReady))
            .map[ProviderRouter](makeFunc1(buildRouterPorts))
            .distinctUntilChanged()
            .takeUntil(mark)

        /**
          * An [[Observable]] that emits [[ProviderRouter]] notifications with
          * the ports of the current router.
          */
        val observable = Observable.create[ProviderRouter](new OnSubscribe[ProviderRouter] {
            override def call(child: Subscriber[_ >: ProviderRouter]): Unit = {
                // The subscription is performed on the VT thread.
                vt.vtExecutor.execute(makeRunnable {
                    providerRouterObservable subscribe child
                })
            }
        })

        /**
          * Completes this router state by removing the current gateway host
          * from the provider router's gateway table.
          */
        def complete(): Unit = {
            mark.onCompleted()
        }

        /**
          * @return True when the router and all its ports have been loaded
          *         from storage.
          */
        def isReady(any: Any): Boolean = {
            (currentRouter ne null) && portTracker.areRefsReady
        }

        /**
          * Handles updates for the current router.
          */
        private def routerUpdated(router: Router): Unit = {
            val portIds = fromProtoList(router.getPortIdsList)

            log debug s"Router $routerId updated with ports: $portIds"
            currentRouter = router
            portTracker.requestRefs(portIds.asScala: _*)
        }

        /**
          * Handles the router deletion, by completing the output observable.
          */
        private def routerDeleted(): Unit = {
            log debug s"Router $routerId deleted"
            portTracker.completeRefs()
        }

        /**
          * Builds a [[ProviderRouter]] notification with the ports of the current
          * router.
          */
        private def buildRouterPorts(any: Any): ProviderRouter = {
            if (portTracker.currentRefs.isEmpty) {
                defaultRouter
            } else {
                val ports = new util.HashMap[UUID, UUID](
                    portTracker.currentRefs.size)
                for ((portId, port) <- portTracker.currentRefs
                     if port.hasPeerId) yield {
                    ports.put(portId, port.getPeerId.asJava)
                }
                log debug s"Provider router $routerId interior ports: $ports"

                ProviderRouter(routerId, ports)
            }
        }
    }

}

/**
  * Manages the FIP64 topology for the provider router and provides
  * notifications with the set of gateways that share the state for a given
  * uplink port.
  */
private[vpp] trait VppProviderRouter { this: VppExecutor =>

    private val uplinks = new util.HashMap[UUID, UplinkState]
    private val routers = new util.HashMap[UUID, RouterState]()

    private val providerRouterSubject = PublishSubject.create[ProviderRouter]

    /**
      * An [[Observable]] that emits notifications for a provider router. The
      * notifications are emitted on the virtual topology thread.
      */
    protected val providerRouterObservable = providerRouterSubject.asObservable()

    protected def hostId: UUID

    protected def vt: VirtualTopology

    protected def log: Logger

    private def uplinkObserver(portId: UUID): Observer[Set[UUID]] = {
        new Observer[Set[UUID]] {
            override def onNext(hostIds: Set[UUID]): Unit = {
                // This notification is received on the VT thread, send it to
                // the VPP executor thread.
                send(Gateways(portId, hostIds))
            }

            override def onError(e: Throwable): Unit = {
                log.warn(s"Unhandled error on uplink $portId state", e)
            }

            override def onCompleted(): Unit = {
                log debug s"Uplink $portId state completed"
            }
        }
    }

    private def routerObserver(state: RouterState) = new Observer[ProviderRouter] {

        def routerId = state.routerId

        override def onNext(ports: ProviderRouter): Unit = {
            providerRouterSubject onNext ports
        }

        override def onError(e: Throwable): Unit = {
            log.warn(s"Unhandled error on provider router $routerId state", e)
            providerRouterSubject onNext state.defaultRouter
        }

        override def onCompleted(): Unit = {
            log debug s"Provider router $routerId state completed"
            providerRouterSubject onNext state.defaultRouter
        }
    }

    /**
      * Adds a new uplink port for a provider router. If the provider router is
      * new, the method creates a new [[RouterState]] for this router.
      *
      * This method must be called from the VT executor thread.
      */
    @NotThreadSafe
    protected def addUplink(portId: UUID, routerId: UUID,
                            groupPortIds: JList[UUID]): Unit = {
        vt.assertThread()
        if (!routers.containsKey(routerId)) {
            log debug s"Create state for provider router $routerId"
            val routerState = new RouterState(routerId, hostId, vt, log)
            routerState.observable subscribe routerObserver(routerState)

            routers.put(routerId, routerState)
        }
        val uplinkState = new UplinkState(portId, routerId, groupPortIds, vt, log)
        uplinkState.observable subscribe uplinkObserver(portId)

        uplinks.put(portId, uplinkState)
    }

    /**
      * Removes an uplink port for a provider router. If this is the last uplink
      * port for the given provider router, the method completes the
      * corresponding [[RouterState]].
      *
      * This method must be called from the VT executor thread.
      */
    @NotThreadSafe
    protected def removeUplink(portId: UUID): Unit = {
        vt.assertThread()
        val uplinkState = uplinks.remove(portId)
        if (uplinkState ne null) {
            uplinkState.complete()

            if (!hasUplinkForRouter(uplinkState.routerId)) {
                val routerState = routers.remove(uplinkState.routerId)
                if (routerState ne null) {
                    routerState.complete()
                }
            }
        }
    }

    /**
      * This method must be called from the VT executor thread.
      * @return The uplink port active on the current host that is reachable
      *         from the given tenant router port or `null` if there is no
      *         such port.
      */
    @NotThreadSafe
    protected def uplinkPortFor(downlinkPortId: UUID): UUID = {
        // TODO: Assume that there is only one uplink port per physical
        // TODO: gateway and the uplink is reachable from this tenant router
        // TODO: port. Further work should observer the virtual topology between
        // TODO: the uplink and the tenant router and update reachability.
        vt.assertThread()
        if (uplinks.isEmpty) {
            log warn s"No uplink ports: ignoring FIP64 for port $downlinkPortId"
            null
        } else if (uplinks.size() > 1) {
            log warn "Multiple uplinks per physical gateway not supported: " +
                     s"ignoring FIP64 for port $downlinkPortId"
            null
        } else {
            uplinks.entrySet().iterator().next().getKey
        }
    }

    /**
      * This method must be called from the VT executor thread.
      * @return The list of uplink ports that share the NAT64 pool with the
      *         given uplink port. These are all the uplink ports of the
      *         provider router.
      */
    @NotThreadSafe
    protected def uplinkPortsFor(uplinkPortId: UUID): JList[UUID] = {
        vt.assertThread()
        val uplinkState = uplinks.get(uplinkPortId)
        if (uplinkState ne null) {
            // Note: We return the ports in the stateful port group of the
            // uplink port, since in practice this is the same as all uplink
            // ports.
            uplinkState.groupPortIds
        } else {
            Collections.emptyList()
        }
    }

    /**
      * @return True if there is at least one uplink for the given provider
      *         router.
      */
    private def hasUplinkForRouter(routerId: UUID): Boolean = {
        val iterator = uplinks.entrySet().iterator()
        while (iterator.hasNext) {
            if (iterator.next().getValue.routerId == routerId)
                return true
        }
        false
    }

}
