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

import java.util
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors._
import java.util.{Objects, UUID}

import scala.collection.JavaConversions._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

import com.lmax.disruptor.util.DaemonThreadFactory
import org.slf4j.LoggerFactory
import rx.schedulers.Schedulers
import rx.subscriptions.CompositeSubscription
import rx.{Observable, Observer, Subscription}

import org.midonet.cluster.data.storage.{NotFoundException, StateKey}
import org.midonet.cluster.models.Topology.{Host, TunnelZone}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend._
import org.midonet.cluster.services.vxgw.FloodingProxyHerald.FloodingProxy
import org.midonet.cluster.services.vxgw.FloodingProxyManager.{HostFpState, MaxFpRetries}
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.cluster.util.logging.ProtoTextPrettifier.makeReadable
import org.midonet.cluster.util.{IPAddressUtil, UUIDUtil}
import org.midonet.cluster.VxgwLog
import org.midonet.packets.IPv4Addr
import org.midonet.util.functors._

object FloodingProxyManager {

    /** Max number of attempts to write a flooding proxy */
    val MaxFpRetries = 3

    case class HostFpState(host: Host, tzId: UUID, isAlive: Boolean,
                           sub: Subscription) {

        override def toString: String = {
            s"HostFpState [ host: ${makeReadable(host)}, tzId: $tzId, " +
            s"isAlive: $isAlive ]"
        }

        /** Partial comparison, we don't care about all fields.
          *
          * For example, if the list of bindings or host groups in the host
          * changes we don't consider that a new FP calculation is required.
          */
        override def equals(o: Any): Boolean = o match {
            case null => false
            case HostFpState(thatHost, thatTzId, thatIsAlive, _) =>
                if (isAlive != thatIsAlive || tzId != thatTzId)
                    return false
                if (host == null)
                    return thatHost != null
                // subset of host properties relevant for the diff:
                host.getFloodingProxyWeight == thatHost.getFloodingProxyWeight &&
                    host.getId == thatHost.getId &&
                    host.getTunnelZoneIdsList == thatHost.getTunnelZoneIdsList
            case _ => false
        }
    }
}


/** This class is responsible for tracking all VTEP tunnel zones and ensure
  * Flooding Proxy is being calculated and published.
  *
  * The manager exposes two methods, start and stop, that allow controlling
  * when the flooding proxy election process is active.  Invoking start will
  * start watching all VTEP tunnel zones and choosing the right FP out of its
  * members, based on their flooding proxy weight.  Invoking stop will halt
  * the process and render this manager unusable.
  */
class FloodingProxyManager(backend: MidonetBackend) {

    private val log = LoggerFactory.getLogger(VxgwLog)

    private val store = backend.store
    private val stateStore = backend.stateStore

    // An index of each host we're tracking, plus its state
    private val trackedHosts = new ConcurrentHashMap[UUID,
        FloodingProxyManager.HostFpState]

    // We will process all notifications on this thread
    private val executor = newSingleThreadExecutor(DaemonThreadFactory.INSTANCE)
    private val rxScheduler = Schedulers.from(executor)
    private implicit val ec = ExecutionContext.fromExecutor(executor)

    // He'll spread the word about our decisions to the rest of MidoNet
    private val _herald = new WritableFloodingProxyHerald(backend)

    // All the subscriptions relevant to us
    private val subscriptions = new CompositeSubscription()

    // This Observer will track all tunnel zones, and ensure that the allHosts
    // subject emits all updates for all hosts that belong to them.
    private val tzObserver = new Observer[TunnelZone] {
        override def onError(t: Throwable): Unit = {
            log.warn("Tunnel zone update stream failed")
        }
        override def onCompleted(): Unit = {
            // The tunnel zone is removed, so all hosts should no longer be
            // watched.  We don't really need to do anything because the
            // deletion of the TZ will cascade a change in the Host backrefs,
            // so the host watcher can deal with its own removal.
            log.debug("Tunnel zone was removed")
        }
        override def onNext(tz: TunnelZone): Unit = {
            if (tz.getType == TunnelZone.Type.VTEP) {
                log.debug(s"Tunnel zone was modified: ${fromProto(tz.getId)}")
                ensureHostsTracked(tz)
            }
        }
    }

    // This Observer tracks all hosts that belong to any VTEP tunnel zone.
    // Whenever a new one is emitted (that is: it's updated), we'll find out
    // what VTEP tunnel zone it belongs to, and recompute the flooding proxy.
    private val hostObserver = new Observer[HostFpState] {
        override def onCompleted(): Unit = {
            // We ignore these: host deletions will trigger a removal of them
            // from the tunnel zone, hence an update on the tunnel zone.
        }
        override def onError(throwable: Throwable): Unit = { /* Irrelevant */ }
        override def onNext(newState: HostFpState): Unit = {
            val id = fromProto(newState.host.getId)
            trackedHosts.get(id) match {
                case null =>
                case HostFpState(host, tzId, isAlive, subscription) =>
                    val currTzs = newState.host.getTunnelZoneIdsList
                    if (!currTzs.contains(UUIDUtil.toProto(tzId))) {
                        log.debug(s"Host is no longer on tunnel zone $tzId")
                        // We don't trigger an FP recalculation as the Tz
                        // itself should notify a change and do it
                        subscription.unsubscribe()
                        subscriptions.remove(subscription)
                    }
                    val oldState = trackedHosts.replace(id, newState)
                    cacheAndPublishFloodingProxy(tzId)
                    log.debug("Host config or state changed from" +
                              s"$oldState to $newState")
            }
        }
    }

    /** The [[FloodingProxyHerald]] that exposes the flooding proxies */
    val herald = new FloodingProxyHerald(backend, None)

    /** Starts watching the VTEP tunnel zone. */
    def start(): Unit = {
        log.info("Flooding proxy manager is started")
        subscriptions.add(
            // backpressure omitted here, we're not expecting > 128 TZs!
            Observable.merge(store.observable(classOf[TunnelZone]))
                      .observeOn(rxScheduler)
                      .subscribe(tzObserver))
        herald.start()
    }

    /** Stop tracking VTEP tunnel zones */
    def stop(): Unit = {
        subscriptions.unsubscribe()
        subscriptions.clear()
        trackedHosts.clear()
        log.info("Flooding proxy manager is stopped")
    }

    /** Ensure that the host is tracked on the given tunnel zone.  Note that
      * this method is only going to execute on our scheduler, so we don't need
      * to be thread safe.
      *
      * The method will also trigger an update of the FP if the host was not
      * already known on the tunnel zone.
      */
    private def ensureHostsTracked(tz: TunnelZone): Unit = {
        val tzId = fromProto(tz.getId)
        val knownIds = new java.util.ArrayList[UUID](tz.getHostIdsCount)
        tz.getHostIdsList.foreach { protoHostId =>
            val hostId = fromProto(protoHostId)
            knownIds.add(hostId)
            log.debug(s"Host $hostId is on tunnel zone $tzId")
            if (!trackedHosts.contains(hostId)) {
                trackHost(hostId, tzId)
            }
        }

        val toRemove = trackedHosts.keys().filterNot(knownIds.contains)
        toRemove.foreach { hostId =>
            log.debug(s"Stop tracking host $hostId, no longer in zone $tzId")
            val hostFpState = trackedHosts.remove(hostId)
            hostFpState.sub.unsubscribe()
            herald.lookup(tzId) foreach { fp =>
                if (fp.hostId == hostId) {
                    log.debug("Host was current flooding proxy, recalculate")
                    cacheAndPublishFloodingProxy(tzId)
                }
            }
        }
    }

    private def trackHost(id: UUID, onTz: UUID): Unit  = {
        val obs = store.observable(classOf[Host], id)
        val stateObs = stateStore.keyObservable(id.toString, classOf[Host], id,
                                                AliveKey)
        val sub = new CompositeSubscription
        val combiner = makeFunc2[Host, StateKey, HostFpState] { (h, s) =>
            HostFpState(h, onTz, s.nonEmpty, sub)
        }

        trackedHosts.put(id, HostFpState(null, onTz, isAlive = false, sub))

        // We need to keep the individual subscription to this host in case
        // it is removed from the tunnel zone, so that we can unsubscribe
        // just from it
        sub.add (
            Observable.combineLatest[Host, StateKey, HostFpState](obs, stateObs, combiner)
                      .distinctUntilChanged()
                      .observeOn(rxScheduler)
                      .subscribe(hostObserver)
        )

        // We also add it to the global subscriptions, so we can unsubscribe
        // at once from everything if the service is stopped
        subscriptions.add(sub)
    }

    /** Trigger the recalculation of a Flooding Proxy for the given tunnel zone,
      * then cache the result and publish the change in the flooding proxy
      * observable
      */
    private def cacheAndPublishFloodingProxy(tzId: UUID,
                                             retries: Int = MaxFpRetries)
    : Unit = recalculateFpFor(tzId).onComplete {
        case Success(null) =>
            _herald.announce(FloodingProxy(tzId, null, null), _ => {})
        case Success(fp) =>
            log.debug("Announcing flooding proxy: {}", fp)
            _herald.announce(fp, t => {
                cacheAndPublishFloodingProxy(tzId, retries - 1)
            })
        case Failure(t) => log.warn("Error calculating flooding proxy", t)
    }

    /** Returns a Future with the new flooding proxy for the given tunnel
      * zone.  The future is always successful, returning a null if there any
      * errors that prevent the FP from being calculated.
      */
    private def recalculateFpFor(tzId: UUID, retries: Int = MaxFpRetries)
    : Future[FloodingProxy] = {
        log.debug(s"Recalculating flooding proxy for tunnel zone $tzId")
        store.get(classOf[TunnelZone], tzId) // should be cached locally
             .map { loadLiveHosts }
             .map { hosts =>
                FloodingProxyCalculator.calculate(tzId, hosts).getOrElse {
                    log.debug(s"No flooding proxy available on zone: $tzId")
                    FloodingProxy(tzId, null, null)
                }
             }.recoverWith[FloodingProxy] {
                 case t: NotFoundException if t.clazz == classOf[TunnelZone] =>
                     log.debug("Tunnel zone {} deleted: clearing flooding proxy",
                               tzId)
                     Future.successful(null)
                 case NonFatal(t) if retries > 0 =>
                     log.warn("Failed to calculate flooding proxy for " +
                              s"tunnel zone $tzId, ($retries) retries left.", t)
                     recalculateFpFor(tzId, retries - 1)
                 case NonFatal(t) =>
                     log.error("Failed to calculate flooding proxy for " +
                               s"tunnel zone $tzId, no retries left.", t)
                     Future.successful(null)
             }
    }

    private def loadLiveHosts(tz: TunnelZone): util.HashMap[Host, IPv4Addr]
    = {
        var hosts = new util.HashMap[Host, IPv4Addr]
        // We're optimizing here to avoid looping over both hostIdsList and
        // hosts.  As we assume that the host ids will be in both places,
        // we're examining the members only
        tz.getHostsList.foreach { hostToIp =>
            val id = fromProto(hostToIp.getHostId)
            val ip = IPAddressUtil.toIPv4Addr(hostToIp.getIp)
            try {
                val state = trackedHosts.get(id)
                if (state != null && state.isAlive) {
                    hosts += state.host -> ip
                } else {
                    log.debug(s"Host $id is not eligible for flooding proxy " +
                              s"($state)")
                }
            } catch {
                case NonFatal(t) => log.warn(s"Host $id could not be retrieved")
            }
        }
        hosts
    }

}
