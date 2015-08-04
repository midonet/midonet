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

package org.midonet.cluster.services.vxgw

import java.util.{HashSet, ArrayList, HashMap, UUID}

import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory.getLogger
import rx.observables.GroupedObservable
import rx.subscriptions.Subscriptions
import rx.{Subscription, Subscriber, Observable, Observer}

import org.midonet.cluster.data.storage.{SingleValueKey, StateKey}
import org.midonet.cluster.models.Topology.{Host, TunnelZone}
import org.midonet.cluster.models.Topology.TunnelZone.Type.VTEP
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend.FloodingProxyKey
import org.midonet.cluster.services.vxgw.FloodingProxyHerald.FloodingProxy
import org.midonet.cluster.util.{UUIDUtil, selfHealingObservable}
import org.midonet.packets.IPv4Addr
import org.midonet.util.functors.{makeAction0, makeFunc1}

object FloodingProxyHerald {

    def deserialize(s: String): FloodingProxy = {
        val split = s.split("#")
        FloodingProxy(UUID.fromString(split(0)), IPv4Addr.fromString(split(1)))
    }

    // Note that IPv4 is intentional, as this feature is only for VxGW, and
    // here IPv6 is not supported.
    case class FloodingProxy(hostId: UUID, tunnelIp: IPv4Addr) {
        private val serialized = s"$hostId#$tunnelIp"
        override def toString = serialized
    }
}

/** This class can be used to retrieve flooding proxies from any component in
  * MidoNet. Flooding Proxies are designated by the VxLAN Gateway Service
  * running in MidoNet cluster nodes.
  */
class FloodingProxyHerald(backend: MidonetBackend, hostFilter: Option[UUID]) {

    protected val log = Logger(getLogger("org.midonet.cluster.flooding-proxies"))
    private val lock = new Object
    @volatile private var fpIndex = new HashMap[UUID, FloodingProxy]
    @volatile private var floodingProxies = new ArrayList[FloodingProxy]

    def lookup(tzId: UUID): FloodingProxy =
        fpIndex.get(tzId)

    def all: ArrayList[FloodingProxy] =
        floodingProxies

    private def updateFps(): Unit = {
        val newFps = new ArrayList[FloodingProxy](fpIndex.values())
        floodingProxies = newFps
    }

    private def addFp(tzId: UUID, fp: FloodingProxy): Unit = lock.synchronized {
        val index = fpIndex.clone().asInstanceOf[HashMap[UUID, FloodingProxy]]
        index.put(tzId, fp)
        log.debug("Zone {} new flooding proxy {} at {}", tzId, fp.hostId, fp.tunnelIp)
        fpIndex = index
        updateFps()
    }

    private def removeFp(tzId: UUID): Unit =  lock.synchronized {
        val index = fpIndex.clone().asInstanceOf[HashMap[UUID, FloodingProxy]]
        val oldFp = index.remove(tzId)
        log.debug("Zone {} loses flooding proxy (was: {})", tzId, oldFp)
        fpIndex = index
        updateFps()
    }

    private def tzObserver(tzId: UUID) = {
        val s = new Subscriber[StateKey] {
            override def onCompleted(): Unit =
                removeFp(tzId)
            override def onError(throwable: Throwable): Unit = {}
            override def onNext(t: StateKey): Unit = t match {
                case SingleValueKey(_, Some(v), _) =>
                    addFp(tzId, FloodingProxyHerald.deserialize(v))
                case SingleValueKey(_, None, _) =>
                    removeFp(tzId)
                case _ =>
            }
        }
        s.add(Subscriptions.create(makeAction0 {
            s.onCompleted()
        }))
        s
    }

    private val tzones = new HashMap[UUID, Subscription]

    /**
     * We subscribe to either all tunnel zones or a host's tunnel zones.
     * These tunnel zones are mapped to the corresponding FloodingProxy and
     * are used to update a snapshot of the state.
     * We unsubscribe to a TunnelZone when it is deleted or when the host
     * is removed from it.
     */
    hostFilter match {
        case None =>
            Observable.merge(selfHealingObservable[TunnelZone](backend.store))
                .groupBy[UUID](makeFunc1 { case t: TunnelZone =>
                    UUIDUtil.fromProto(t.getId)
                })
                .subscribe(new Observer[GroupedObservable[UUID, TunnelZone]] {
                    override def onCompleted(): Unit = { }
                    override def onError(e: Throwable): Unit =
                        log.warn("Unexpected error", e)
                    override def onNext(t: GroupedObservable[UUID, TunnelZone]): Unit =
                        subscribeTunnelZone(t.getKey, t)
            })
        case Some(hostId) =>
            selfHealingObservable[Host](backend.store, hostId)
                .subscribe(hostObserver)
    }

    private val hostObserver = new Observer[Host] {
        override def onCompleted(): Unit = {
            log.debug("Host observable completed")
            clear()
        }
        override def onError(e: Throwable): Unit = {
            log.warn("Error on host observable", e)
            clear()
        }
        override def onNext(t: Host): Unit = {
            val newTunnelZones = t.getTunnelZoneIdsList()
            val existingTunnelZones = new HashSet(tzones.keySet())

            val newZonesIt = newTunnelZones.iterator()
            while (newZonesIt.hasNext) {
                val tzId = UUIDUtil.fromProto(newZonesIt.next())
                if (existingTunnelZones.remove(tzId)) {
                    val s = subscribeTunnelZone(
                        tzId,
                        selfHealingObservable[TunnelZone](backend.store, tzId))
                    tzones.put(tzId, s)
                }
            }
            val toRemove = existingTunnelZones.iterator()
            while (toRemove.hasNext) {
                tzones.remove(toRemove.next()).unsubscribe()
            }
        }
    }

    private def clear(): Unit = {
        val toRemove = tzones.entrySet().iterator()
        while (toRemove.hasNext) {
            toRemove.next().getValue.unsubscribe()
        }
        tzones.clear()
    }

    private def subscribeTunnelZone(tzId: UUID, obs: Observable[TunnelZone]) =
        obs
            .filter(makeFunc1 { t: TunnelZone => t.getType == VTEP })
            .flatMap(makeFunc1 { t: TunnelZone =>
                log.debug(s"Mapping to flooding proxy for $tzId")
                backend.stateStore
                    .keyObservable(
                        classOf[TunnelZone], tzId, FloodingProxyKey)
            })
            .subscribe(tzObserver(tzId))
}
