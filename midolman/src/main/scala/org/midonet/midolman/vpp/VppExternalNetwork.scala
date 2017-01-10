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
import java.util.UUID

import javax.annotation.concurrent.NotThreadSafe

import scala.collection.JavaConverters._

import rx.{Observable, Subscriber}
import rx.Observable.OnSubscribe
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject

import org.midonet.cluster.models.Commons.IPVersion
import org.midonet.cluster.models.Neutron.{NeutronNetwork, NeutronSubnet}
import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.util.IPSubnetUtil
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.topology.{StoreObjectReferenceTracker, VirtualTopology}
import org.midonet.midolman.vpp.VppExternalNetwork.NetworkState
import org.midonet.midolman.vpp.VppFip64.Notification
import org.midonet.midolman.vpp.VppProviderRouter.ProviderRouter
import org.midonet.packets.IPv6Subnet
import org.midonet.util.functors.{makeAction1, makeFunc1}
import org.midonet.util.logging.Logger

object VppExternalNetwork {

    /**
      * Indicates that an external network was added to a provider router.
      */
    case class AddExternalNetwork(networkId: UUID)
        extends Notification

    /**
      * Indicates that an external network was removed from a provider router.
      */
    case class RemoveExternalNetwork(networkId: UUID) extends Notification

    /**
      * Manages the state for a network connected to a provider router. This
      * monitors the network object and returns [[AddExternalNetwork]] and
      * [[RemoveExternalNetwork]] if the network is an external network.
      */
    private class NetworkState(networkId: UUID, vt: VirtualTopology,
                               log: Logger) {

        private final val removeObservable =
            Observable.just[Notification](RemoveExternalNetwork(networkId))

        private val mark = PublishSubject.create[Notification]
        private val scheduler = Schedulers.from(vt.vtExecutor)
        private val subnetTracker =
            new StoreObjectReferenceTracker[NeutronSubnet](
                vt, classOf[NeutronSubnet], log)

        private val cleanup = Observable.create(new OnSubscribe[Notification] {
            override def call(child: Subscriber[_ >: Notification]): Unit = {
                completeNetwork(child)
            }
        })

        private var currentNetwork: NeutronNetwork = _
        private var previousNetwork: NeutronNetwork = _

        private val networkObservable = vt.store
            .observable(classOf[NeutronNetwork], networkId)
            .observeOn(scheduler)
            .doOnNext(makeAction1(networkUpdated))

        /**
          * An [[Observable]] that emits notifications when an external
          * network is added or removed for a provider router.
          */
        val observable = Observable
            .merge(subnetTracker.refsObservable, networkObservable)
            .observeOn(vt.vtScheduler)
            .filter(makeFunc1(isReady))
            .concatMap(makeFunc1(buildNotification))
            .onErrorResumeNext(Observable.empty())
            .takeUntil(mark)
            .concatWith(cleanup)

        /**
          * Completes this [[NetworkState]] when the network is no longer
          * connected to a provider router.
          */
        def complete(): Unit = {
            mark.onCompleted()
        }

        /**
          * @return True if the Neutron network and all its subnets have been
          *         loaded from storage.
          */
        private def isReady(any: Any): Boolean = {
            (currentNetwork ne null) && subnetTracker.areRefsReady
        }

        /**
          * Handles updates to the [[NeutronNetwork]] and updates the monitored
          * Neutron subnets.
          */
        private def networkUpdated(network: NeutronNetwork): Unit = {
            val subnetIds = network.getSubnetsList.asScala.map(_.asJava).toSet
            log debug s"Network $networkId updated with subnetworks: $subnetIds"

            currentNetwork = network
            subnetTracker.requestRefs(subnetIds)
        }

        /**
          * Handles updates to the [[NeutronNetwork]] and returns an
          * [[Observable]] that emits add/remove notifications.
          */
        private def buildNotification(any: Any)
        : Observable[Notification] = {
            log debug s"Network $networkId external: ${currentNetwork.getExternal}"

            val subnets = for (subnet <- subnetTracker.currentRefs.values
                 if subnet.getCidr.getVersion == IPVersion.V6) yield {
                IPSubnetUtil.fromV6Proto(subnet.getCidr)
            }

            val result =
                if (previousNetwork eq null) {
                    if (currentNetwork.getExternal)
                        Observable.just[Notification](AddExternalNetwork(networkId))
                    else Observable.empty[Notification]()
                } else if (previousNetwork.getExternal !=
                           currentNetwork.getExternal) {
                    if (currentNetwork.getExternal)
                        Observable.just[Notification](AddExternalNetwork(networkId))
                    else
                        removeObservable
                } else {
                    Observable.empty[Notification]()
                }
            previousNetwork = currentNetwork
            result
        }

        /**
          * Handles the completion of this network state, such that if this
          * network was previously added as an external network, it will
          * emit a [[RemoveExternalNetwork]] notification.
          */
        private def completeNetwork(child: Subscriber[_ >: Notification]): Unit = {
            if ((currentNetwork ne null) && currentNetwork.getExternal) {
                child onNext RemoveExternalNetwork(networkId)
            }
            child.onCompleted()
        }
    }

}

/**
  * A trait that manages the external networks for the provider routers. This
  * trait exposes an [[Observable]] for derived classes that emits notifications
  * when an external network is added to or removed from a provider router.
  */
private[vpp] trait VppExternalNetwork {

    protected def vt: VirtualTopology

    protected def log: Logger

    private val portIds = new util.HashSet[UUID](4)
    private val networkIds = new util.HashSet[UUID](4)

    private val routers = new util.HashMap[UUID, ProviderRouter](4)
    private val networks = new util.HashMap[UUID, NetworkState](4)

    private val portSubject = PublishSubject.create[Port]

    private val portsTracker =
        new StoreObjectReferenceTracker(vt, classOf[Port], log)

    private val portObservable = Observable
        .merge(portsTracker.refsObservable, portSubject)
        .filter(makeFunc1(arePortsReady))
        .concatMap(makeFunc1(portsUpdated))

    private val networkSubject = PublishSubject.create[Observable[Notification]]

    private val networkObservable = Observable.merge(networkSubject)

    /**
      * An [[Observable]] that emits notifications when an external network is
      * add to or removed from an external network. This observable is further
      * used to (1) configure the state of the current gateway for this
      * external network and (2) fetch the external network downlink FIP64
      * entries.
      */
    protected val externalNetworkObservable =
        Observable.merge(networkObservable, portObservable)

    @NotThreadSafe
    protected def updateProviderRouter(router: ProviderRouter): Unit = {
        if (!router.ports.isEmpty) {
            routers.put(router.routerId, router)
        } else {
            routers.remove(router.routerId)
        }

        // Consolidate the interior ports from all provider routers.
        portIds.clear()
        val iterator = routers.entrySet().iterator()
        while (iterator.hasNext) {
            portIds.addAll(iterator.next().getValue.ports.values())
        }

        log debug s"Updating provider routers with peer ports $portIds"

        portsTracker.requestRefs(portIds.asScala)
        // Emit a null port to handle the removal of peer ports.
        portSubject onNext null
    }

    /**
      * @return True if the peer ports for the provider routers have been
      *         loaded from storage.
      */
    private def arePortsReady(port: Port): Boolean = {
        val ready = portsTracker.areRefsReady
        log debug s"Provider routers peer ports ready: $ready"
        ready
    }

    /**
      * Handles updates to a peer port for a provider router. The method uses
      * the peer port network identifier (if the peer port is a bridge port)
      * to update the set of networks connected to the provider router and
      * start/stop monitoring those networks).
      */
    private def portsUpdated(port: Port): Observable[Notification] = {
        networkIds.clear()
        for (port <- portsTracker.currentRefs.values if port.hasNetworkId) {
            networkIds.add(port.getNetworkId.asJava)
        }

        log debug s"Updating networks: $networkIds"

        val idIterator = networkIds.iterator()
        while (idIterator.hasNext) {
            val networkId = idIterator.next()
            if (!networks.containsKey(networkId)) {
                log debug s"Adding state for network $networkId"
                val networkState = new NetworkState(networkId, vt, log)
                networks.put(networkId, networkState)
                networkSubject onNext networkState.observable
            }
        }

        val entryIterator = networks.entrySet().iterator()
        while (entryIterator.hasNext) {
            val entry = entryIterator.next()
            if (!networkIds.contains(entry.getKey)) {
                log debug s"Removing network ${entry.getKey}"
                entry.getValue.complete()
                entryIterator.remove()
            }
        }

        // Mask all port notifications.
        Observable.empty()
    }

}
