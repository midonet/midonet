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
import java.util.concurrent.{ConcurrentHashMap, Executors}

import scala.collection.JavaConversions._
import scala.concurrent.Future

import org.slf4j.LoggerFactory
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject
import rx.subscriptions.CompositeSubscription
import rx.{Observable, Observer, Subscription}

import org.midonet.cluster.data.storage.{SingleValueKey, StateKey}
import org.midonet.cluster.models.Topology.{Host, TunnelZone}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend._
import org.midonet.cluster.services.vxgw.VtepTunnelZoneTracker.FloodingProxyUpdate
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.util.functors._

object VtepTunnelZoneTracker {

    object FloodingProxyUpdate {
        def apply(tzId: UUID, fpId: UUID): FloodingProxyUpdate = fpId match {
            case null => FloodingProxyRemoved(tzId)
            case id => FloodingProxyChanged(tzId, id)
        }
    }

    abstract class FloodingProxyUpdate(tz: UUID)

    case class FloodingProxyRemoved(tz: UUID) extends FloodingProxyUpdate(tz)
    case class FloodingProxyChanged(tz: UUID, hostId: UUID)
        extends FloodingProxyUpdate(tz)

}

/** This class is responsible for tracking all VTEP tunnel zones and ensure
  * Flooding Proxy is being calculated and published.
  */
class VtepTunnelZoneTracker(backend: MidonetBackend) {

    private val log = LoggerFactory.getLogger(vxgwLog)

    private val MAX_FP_RETRIES = 3

    // Host -> VTEP Tz, so that we know what TZ needs to recalculate its
    // flooding proxy
    private val trackedHosts = new ConcurrentHashMap[UUID, (UUID, Subscription)]

    // An index of a tunnel zone to its current flooding proxy
    private val fpIndex = new ConcurrentHashMap[UUID, UUID]()

    // The notifications about changes in flooding proxies
    private val fpObservable = PublishSubject.create[FloodingProxyUpdate]

    // An observable to emit all hosts that are being modified
    private val allHosts = PublishSubject.create[Host]()

    // All the subscriptions relevant to us
    private val subscriptions = new CompositeSubscription()

    // We will process all notifications on this thread
    private val ec = Executors.newSingleThreadExecutor()
    private val rxScheduler = Schedulers.from(ec)

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
            if (tz.getType != TunnelZone.Type.VTEP) {
                return
            }
            val tzId = fromProto(tz.getId)
            log.info(s"VTEP tunnel zone updated: $tzId")
            tz.getHostIdsList.foreach { hostId =>
                ensureTracked(fromProto(hostId), tzId)
            }
        }
    }

    // This Observer tracks all hosts that belong to any VTEP tunnel zone.
    // Whenever a new one is emitted (that is: it's updated), we'll find out
    // what VTEP tunnel zone it belongs to, and recompute the flooding proxy.
    private val hostObserver = new Observer[Host] {
        override def onCompleted(): Unit = { /* Irrelevant */ }
        override def onError(throwable: Throwable): Unit = { /* Irrelevant */ }
        override def onNext(h: Host): Unit = {
            val hostId = fromProto(h.getId)
            trackedHosts.get(hostId) match {
                case null =>
                    log.info(s"Host $hostId is not in a VTEP tunnel zone")
                case (tzId, subscription) =>
                    if (!h.getTunnelZoneIdsList.exists(fromProto(_) == tzId)) {
                        log.debug(s"Host is no longer on tunnel zone $tzId")
                        trackedHosts.remove(hostId)
                        subscription.unsubscribe()
                    }
                    cacheAndPublishFloodingProxy(tzId)
            }
        }
    }

    private val hostStateObserver = new Observer[StateKey] {
        override def onCompleted(): Unit = {}
        override def onError(throwable: Throwable): Unit = {}
        override def onNext(key: StateKey): Unit =  {
            case SingleValueKey(_, _, hostId) =>
                val tzAndSubscription = trackedHosts.get(hostId)
                if (tzAndSubscription != null) {
                    cacheAndPublishFloodingProxy(tzAndSubscription._1)
                }
        }
    }

    /** Starts watching the VTEP tunnel zone. */
    def start(): Unit = {
        subscriptions.add(allHosts.observeOn(rxScheduler)
                                  .subscribe(hostObserver))
        subscriptions.add(Observable.merge(selfHealingTzObservable())
                                    .observeOn(rxScheduler)
                                    .subscribe(tzObserver))
    }

    /** Stop tracking VTEP tunnel zones */
    def stop(): Unit = {
        subscriptions.unsubscribe()
        fpIndex.clear()
        trackedHosts.clear()
    }

    /** Provide the current flooding proxy for the given tunnel zone, or null
      * if none has been calculated yet.
      */
    def currentFp(tzId: UUID): UUID = fpIndex.get(tzId)

    /** Ensure that the host is tracked on the given tunnel zone.  Returns
      * true if the host was newly tracked.  Note that this method is only
      * going to execute on our scheduler, so we don't need to be thread safe.
      *
      * The method will also trigger an update of the FP if the host was not
      * already known on the tunnel zone.
      */
    private def ensureTracked(id: UUID, onTz: UUID): Unit = {
        val onMap = trackedHosts.getOrElseUpdate(id, {
            val sub = new CompositeSubscription()
            sub.add(selfHealingHostObservable(id).subscribe(allHosts))
            sub.add(backend.stateStore.keyObservable(classOf[Host], id, AliveKey)
                                      .observeOn(rxScheduler)
                                      .subscribe(hostStateObserver))
            val e = (onTz, sub)
            cacheAndPublishFloodingProxy(onTz)
            e
        })
        if (onMap._1 != onTz) {
            log.warn(s"Host $id seems to be in two tunnel zones: " +
                     s"${onMap._1} and $onTz - this is not expected, we're " +
                     s"ignoring $onTz")
        }
    }

    /** Trigger the recalculation of a Flooding Proxy for the given tunnel zone,
      * then cache the result and publish the change in the flooding proxy
      * observable
      */
    private def cacheAndPublishFloodingProxy(tzId: UUID): Unit = {
        recalculateFpFor(tzId).onSuccess { case fp =>
            if (fp == null) fpIndex.remove(tzId)
            else fpIndex.put(tzId, fp)
            fpObservable.onNext(FloodingProxyUpdate(tzId, fp))
        }
    }

    /** Returns a Future with the new flooding proxy for the given tunnel
      * zone.  The future is always successful, returning a null if there any
      * errors that prevent the FP from being calculated.
      */
    private def recalculateFpFor(tzId: UUID, retries: Int = MAX_FP_RETRIES)
    : Future[UUID] = {
        backend.store
            .get(classOf[TunnelZone], tzId) // should be cached locally
            .map { _.getHostIdsList.map(fromProto).filter(isAlive) }
            .flatMap(backend.store.getAll(classOf[Host], _)) // also these
            .map(FloodingProxyCalculator.calculate)
            .recoverWith[UUID] {
                case t if retries > 0 =>
                    log.warn("Failed to calculate flooding proxy for tunnel " +
                             s"zone $tzId, (${retries - 1}) left.", t)
                    recalculateFpFor(tzId, retries - 1)
                case t =>
                    log.error("Failed to calculate flooding proxy for tunnel " +
                              s"zone $tzId, no retries left.", t)
                    Future.successful[UUID](null)
            }
    }

    /** Tells whether the host with the given ID is alive or not */
    private def isAlive(hostId: UUID): Boolean = {
        backend.stateStore.getKey(classOf[Host], hostId, AliveKey)
                          .toBlocking.single().nonEmpty
    }

    /** An Observable that will recover itself if an error is emitted */
    private def selfHealingTzObservable(): Observable[Observable[TunnelZone]] =
        backend.store
               .observable(classOf[TunnelZone])
               .onErrorResumeNext(
                   makeFunc1[Throwable, Observable[Observable[TunnelZone]]] {
                       t => selfHealingTzObservable()
                   }
               )

    /**
     * A Host Observable that will recover itself if an error is emitted.
     */
    private def selfHealingHostObservable(id: UUID): Observable[Host] = {
        backend.store
                .observable(classOf[Host], id)
                .onErrorResumeNext(makeFunc1[Throwable, Observable[Host]] { t =>
                    log.info("Update stream for host $id failed, recovering")
                    selfHealingHostObservable(id)
                }
        )
    }
}
