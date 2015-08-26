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

import java.util.{Objects, UUID}
import java.util.concurrent.{ConcurrentHashMap, Executor}

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory.getLogger
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject
import rx.{Observable, Observer}

import org.midonet.cluster.data.storage.{SingleValueKey, StateKey}
import org.midonet.cluster.models.Topology.TunnelZone
import org.midonet.cluster.models.Topology.TunnelZone.Type.VTEP
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend.FloodingProxyKey
import org.midonet.cluster.services.vxgw.FloodingProxyHerald.FloodingProxy
import org.midonet.cluster.util.{UUIDUtil, selfHealingTypeObservable}
import org.midonet.packets.IPv4Addr
import org.midonet.util.functors.{makeAction1, makeFunc1}

object FloodingProxyHerald {

    /** Deserializes a state string into a FloodingProxy object for the given
      * tunnel zone.
      */
    def deserialize(tunnelZoneId: UUID, s: String): FloodingProxy = {
        val split = s.split("#")
        FloodingProxy(tunnelZoneId, UUID.fromString(split(0)),
                      IPv4Addr.fromString(split(1)))
    }

    // Note that IPv4 is intentional, as this feature is only for VxGW, and
    // here IPv6 is not supported.
    case class FloodingProxy(tunnelZoneId: UUID, hostId: UUID,
                             tunnelIp: IPv4Addr) {
        private val serialized = s"$hostId#$tunnelIp"
        override def toString = serialized

        override def hashCode = Objects.hash(tunnelZoneId, hostId, tunnelIp)
        override def equals(o: Any): Boolean = o match {
            case that: FloodingProxy =>
                Objects.equals(tunnelIp, that.tunnelIp) &&
                    Objects.equals(tunnelZoneId, that.tunnelZoneId) &&
                    Objects.equals(hostId, that.hostId)
            case _ => false
        }
    }
}

/** This class can be used to retrieve flooding proxies from any component in
  * MidoNet. Flooding Proxies are designated by the VxLAN Gateway Service
  * running in MidoNet cluster nodes.
  */
class FloodingProxyHerald(backend: MidonetBackend, executor: Executor) {

    protected val log = Logger(getLogger("org.midonet.cluster.flooding-proxies"))

    private val fpIndex = new ConcurrentHashMap[UUID, Option[FloodingProxy]]()
    private val updateStream = PublishSubject.create[FloodingProxy]()

    /** Returns the current flooding proxy for this tunnel zone.  This method
      * works off a local cache so consider that a lookup here may not have the
      * latest value if the notification from storage hasn't been processed yet.
      */
    def lookup(tzId: UUID): Option[FloodingProxy] = {
        val value = fpIndex.get(tzId)
        if (value == null) None
        else value
    }

    /** Exposes the latest flooding proxy for each VTEP tunnel zone.  Do not
      * worry about errors, we won't emit any.
      */
    val observable = updateStream.asObservable().distinctUntilChanged()

    /** A dedicated observer for the given tunnel zone that will update the
      * internal cache of flooding proxies.  When the tunnel zone is deleted,
      * the cache will be cleaned up.
      */
    private def tzObserver(tzId: UUID) = new Observer[StateKey] {
        override def onCompleted(): Unit = {
            log.debug("Zone {} was deleted", tzId)
            fpIndex.remove(tzId)
            updateStream.onNext(FloodingProxy(tzId, null, null))
        }
        override def onError(throwable: Throwable): Unit = {}
        override def onNext(t: StateKey): Unit = t match {
            case SingleValueKey(_, Some(v), _) =>
                val fp = FloodingProxyHerald.deserialize(tzId, v)
                val oldFp = fpIndex.put(tzId, Some(fp))
                if (oldFp == null || fp != oldFp.orNull) {
                    log.debug(s"Zone $tzId has new flooding proxy $v at " +
                              fp.tunnelIp)
                    updateStream.onNext(fp)
                }
            case SingleValueKey(_, None, _) =>
                val oldFp = fpIndex.put(tzId, None)
                if (oldFp != null) {
                    updateStream.onNext(FloodingProxy(tzId, null, null))
                }
                log.debug("Zone {} loses flooding proxy (was: {})", tzId, oldFp)
            case _ =>
        }
    }

    Observable.merge(selfHealingTypeObservable[TunnelZone](backend.store))
        .filter(makeFunc1 { t: TunnelZone => t.getType == VTEP })
        .observeOn(Schedulers.from(executor))
        .subscribe(makeAction1[TunnelZone] { t =>
            val tzId = UUIDUtil.fromProto(t.getId)
            if (!fpIndex.containsKey(tzId)) {
                log.debug("Start to watch tunnel zone {}", tzId)
                backend.stateStore
                       .keyObservable(classOf[TunnelZone], tzId,
                                      FloodingProxyKey)
                       .subscribe(tzObserver(tzId))
            }
        }
    )

}