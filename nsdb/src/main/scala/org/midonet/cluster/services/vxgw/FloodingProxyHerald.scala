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

import java.util.UUID
import java.util.concurrent.{ConcurrentHashMap, Executor}

import org.slf4j.LoggerFactory.getLogger
import rx.schedulers.Schedulers
import rx.{Observable, Observer}

import org.midonet.cluster.data.storage.{SingleValueKey, StateKey}
import org.midonet.cluster.models.Topology.TunnelZone
import org.midonet.cluster.models.Topology.TunnelZone.Type.VTEP
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend.TunnelZoneKey
import org.midonet.cluster.services.vxgw.FloodingProxyHerald.FloodingProxy
import org.midonet.cluster.util.{UUIDUtil, selfHealingTzObservable}
import org.midonet.packets.IPv4Addr
import org.midonet.util.functors.makeFunc1

object FloodingProxyHerald {

    def deserialize(s: String): FloodingProxy = {
        val split = s.split("_")
        FloodingProxy(UUID.fromString(split(0)), IPv4Addr.fromString(split(1)))
    }

    // Note that IPv4 is intentional, as this feature is only for VxGW, and
    // here IPv6 is not supported.
    case class FloodingProxy(hostId: UUID, tunnelIp: IPv4Addr) {
        val serialized = s"$hostId#$tunnelIp"
        override def toString = serialized
    }
}

/** This class can be used to retrieve flooding proxies from any component in
  * MidoNet.  Flooding Proxies are designated by the VxLAN Gateway Service
  * running in MidoNet cluster nodes.
  */
class FloodingProxyHerald(backend: MidonetBackend, executor: Executor) {

    protected val log = getLogger("org.midonet.cluster.flooding-proxies")

    private val fpIndex = new ConcurrentHashMap[UUID, FloodingProxy]()

    /** Returns the current flooding proxy for this tunnel zone.  This method
      * works off a local cache so consider that a lookup here may not have the
      * latest value if the notification from storage hasn't been processed yet.
      */
    def lookup(tzId: UUID): Option[FloodingProxy] = Option(fpIndex.get(tzId))

    /** A dedicated observer for the given tunnel zone that will update the
      * internal cache of flooding proxies.  When the tunnel zone is deleted,
      * the cache will be cleaned up.
      */
    private def tzObserver(tzId: UUID) = new Observer[StateKey] {
        override def onCompleted(): Unit = {
            log.debug(s"Zone $tzId was deleted")
            fpIndex.remove(tzId)
        }
        override def onError(throwable: Throwable): Unit = {}
        override def onNext(t: StateKey): Unit = t match {
            case SingleValueKey(_, Some(v), _) =>
                val fp = FloodingProxyHerald.deserialize(v)
                log.debug(s"Zone $tzId, new flooding proxy $v at ${fp.tunnelIp}")
                fpIndex.put(tzId, fp)
            case SingleValueKey(_, None, _) =>
                val oldFp = fpIndex.remove(tzId)
                log.debug(s"Zone $tzId loses flooding proxy (was: $oldFp")
            case _ =>
        }
    }

    Observable.merge(selfHealingTzObservable(backend.store))
        .filter(makeFunc1 { t: TunnelZone => t.getType == VTEP })
        .observeOn(Schedulers.from(executor))
        .subscribe(new Observer[TunnelZone] {
            override def onCompleted(): Unit = {}
            override def onError(t: Throwable): Unit = {}
            override def onNext(t: TunnelZone): Unit = {
                val tzId = UUIDUtil.fromProto(t.getId)
                if (!fpIndex.contains(tzId)) {
                    log.debug(s"Watching Zone $tzId")
                    backend.stateStore
                           .keyObservable(classOf[TunnelZone], tzId,
                                          TunnelZoneKey)
                           .subscribe(tzObserver(tzId))
                }
            }
    })

}