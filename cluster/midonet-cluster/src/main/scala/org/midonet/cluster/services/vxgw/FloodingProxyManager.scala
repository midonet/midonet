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
import java.util.UUID
import java.util.concurrent.{ConcurrentHashMap, Executors}

import scala.collection.JavaConversions._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

import org.slf4j.LoggerFactory

import rx.schedulers.Schedulers
import rx.subjects.PublishSubject
import rx.subscriptions.CompositeSubscription
import rx.{Observable, Observer, Subscription}

import org.midonet.cluster.data.storage.{NotFoundException, StateKey}
import org.midonet.cluster.models.Topology.{Host, TunnelZone}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend._
import org.midonet.cluster.services.vxgw.FloodingProxyHerald.FloodingProxy
import org.midonet.cluster.services.vxgw.FloodingProxyManager.{HostFpState, MaxFpRetries}
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.cluster.util.{IPAddressUtil, selfHealingTzObservable}
import org.midonet.packets.IPv4Addr
import org.midonet.util.functors._

object FloodingProxyManager {

    /** Max number of attempts to write a flooding proxy */
    val MaxFpRetries = 3

    case class HostFpState(host: Host, tzId: UUID, isAlive: Boolean,
                           sub: Subscription) {
        def toggle(newAlive: Boolean) = HostFpState(host, tzId, newAlive, sub)
    }

}

/** This class is responsible for tracking all VTEP tunnel zones and ensure
  * Flooding Proxy is being calculated and published.
  */
class FloodingProxyManager(backend: MidonetBackend) {

    private val log = LoggerFactory.getLogger(vxgwLog)

    private val store = backend.store
    private val stateStore = backend.stateStore

    // An index of each host we're tracking, plus its state
    private val trackedHosts = new ConcurrentHashMap[UUID,
        FloodingProxyManager.HostFpState]

    // An observable to emit all hosts that are being modified
    private val allHosts = PublishSubject.create[HostFpState]()

    // We will process all notifications on this thread
    private val executor = Executors.newSingleThreadExecutor()
    private val rxScheduler = Schedulers.from(executor)
    private implicit val ec = ExecutionContext.fromExecutor(executor)

    // He'll spread the word about our decissions to the rest of MidoNet
    private val _herald = new WritableFloodingProxyHerald(backend, executor)

    // All the subscriptions relevant to us
    private val subscriptions = new CompositeSubscription()

    // This Observer will track all tunnel zones, and ensure that the allHosts
    // subject emits all updates for all hosts that belong to them.
    private val tzObserver = new Observer[TunnelZone] {
        override def onError(t: Throwable): Unit = {}
        override def onCompleted(): Unit = {
            // The tunnel zone is removed, so all hosts should no longer be
            // watched.  We don't really need to do anything because the
            // deletion of the TZ will cascade a change in the Host backrefs,
            // so the host watcher can deal with its own removal.
        }
        override def onNext(tz: TunnelZone): Unit = {
            val tzId = fromProto(tz.getId)
            if (tz.getType != TunnelZone.Type.VTEP) {
                log.debug(s"Ignoring tunnel zone $tzId")
                return
            }
            log.info(s"VTEP tunnel zone updated: $tzId")
            ensureHostsTracked(tz)
        }
    }

    // This Observer tracks all hosts that belong to any VTEP tunnel zone.
    // Whenever a new one is emitted (that is: it's updated), we'll find out
    // what VTEP tunnel zone it belongs to, and recompute the flooding proxy.
    private val hostObserver = new Observer[HostFpState] {
        override def onCompleted(): Unit = { /* Irrelevant */ }
        override def onError(throwable: Throwable): Unit = { /* Irrelevant */ }
        override def onNext(newState: HostFpState): Unit = {
            val id = fromProto(newState.host.getId)
            trackedHosts.get(id) match {
                case null => log.info(s"Host $id is not in a VTEP tunnel zone")
                case HostFpState(host, tzId, isAlive, subscription) =>
                    val currTunnelZones =
                        newState.host.getTunnelZoneIdsList.map(fromProto)
                    if (!currTunnelZones.contains(tzId)) {
                        log.debug(s"Host is no longer on tunnel zone $tzId")
                        trackedHosts.remove(id)
                        subscription.unsubscribe()
                    }
                    trackedHosts.replace(id, newState)
                    cacheAndPublishFloodingProxy(tzId)
            }
        }
    }

    /** The [[FloodingProxyHerald]] that exposes the flooding proxies */
    def herald: FloodingProxyHerald = _herald

    /** Starts watching the VTEP tunnel zone. */
    def start(): Unit = {
        subscriptions.add(allHosts.observeOn(rxScheduler)
                                  .subscribe(hostObserver))
        subscriptions.add(Observable.merge(selfHealingTzObservable(store))
                                    .observeOn(rxScheduler)
                                    .subscribe(tzObserver))
    }

    /** Stop tracking VTEP tunnel zones */
    def stop(): Unit = {
        subscriptions.unsubscribe()
        trackedHosts.clear()
    }

    /** Ensure that the host is tracked on the given tunnel zone.  Note that
      * this method is only going to execute on our scheduler, so we don't need
      * to be thread safe.
      *
      * The method will also trigger an update of the FP if the host was not
      * already known on the tunnel zone.
      */
    private def ensureHostsTracked(tz: TunnelZone): Unit = {
        val onTz = fromProto(tz.getId)
        tz.getHostIdsList.foreach { pId =>
            val hostId = fromProto(pId)
            if (!trackedHosts.contains(hostId)) {
                trackHost(hostId, onTz)
            }
        }
    }

    private def trackHost(id: UUID, onTz: UUID): Unit  = {
        log.debug(s"New host $id on VTEP tunnel zone $onTz")
        val obs = selfHealingHostObservable(id)
        val stateObs = stateStore.keyObservable(classOf[Host], id, AliveKey)
        val sub = new CompositeSubscription
        val combiner = makeFunc2[Host, StateKey, HostFpState] {
                           case (h: Host, s: StateKey) =>
                               HostFpState(h, onTz, s.nonEmpty, sub)
                       }
        sub.add (
            Observable.combineLatest[Host, StateKey, HostFpState]
                (obs, stateObs, combiner).subscribe(allHosts)
        )

        trackedHosts.put(id, HostFpState(null, onTz, isAlive = false, sub))
    }

    /** Trigger the recalculation of a Flooding Proxy for the given tunnel zone,
      * then cache the result and publish the change in the flooding proxy
      * observable
      */
    private def cacheAndPublishFloodingProxy(tzId: UUID,
                                             retries: Int = MaxFpRetries)
    : Unit = recalculateFpFor(tzId).onComplete {
        case Success(fp) =>
            _herald.announce(tzId, fp, { _ =>
                cacheAndPublishFloodingProxy(tzId, retries -1 )
            })
        case Failure(t) => log.warn("Error calculating flooding proxy", t)
    }

    /** Returns a Future with the new flooding proxy for the given tunnel
      * zone.  The future is always successful, returning a null if there any
      * errors that prevent the FP from being calculated.
      */
    private def recalculateFpFor(tzId: UUID, retries: Int = MaxFpRetries)
    : Future[FloodingProxy] = {
        store.get(classOf[TunnelZone], tzId) // should be cached locally
             .map { loadLiveHosts }
             .map(hosts => FloodingProxyCalculator.calculate(hosts).orNull)
             .recoverWith[FloodingProxy] {
                 case t: Throwable if retries > 0 =>
                     log.warn("Failed to calculate flooding proxy for " +
                              s"tunnel zone $tzId, (${retries - 1}) left.", t)
                     recalculateFpFor(tzId, retries - 1)
                 case t: Throwable =>
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
                    log.debug(s"Host $id is not eligible for flooding proxy")
                }
            } catch {
                case NonFatal(t) => log.warn(s"Host $id could not be retrieved")
            }
        }
        hosts
    }

    /**
     * A Host Observable that will recover itself if an error is emitted.
     */
    private def selfHealingHostObservable(id: UUID): Observable[Host] = {
        store.observable(classOf[Host], id)
             .onErrorResumeNext ( makeFunc1[Throwable, Observable[Host]] {
                 case t: NotFoundException => Observable.empty()
                 case _ =>
                     log.info(s"Update stream for host $id failed, recover")
                     selfHealingHostObservable(id)
                 }
             )
    }
}
