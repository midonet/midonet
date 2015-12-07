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

package org.midonet.cluster.services.containers.schedulers

import java.util.UUID
import java.util.concurrent.ExecutorService

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory
import rx.Observable
import rx.subjects.PublishSubject

import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology.Host
import org.midonet.cluster.services.DeviceWatcher
import org.midonet.cluster.util.UUIDUtil._

/**
  * This class implements the [[HostSelector]] trait with a simple policy
  * that reports every host that's registered into the NSDB.
  */
class AnywhereHostSelector (store: Storage, scheduler: rx.Scheduler)
                           (implicit context: ExecutionContext)
        extends HostSelector {

    private val log = Logger(
        LoggerFactory.getLogger("org.midonet.containers-selector-anywhere"))

    /* The hosts learned on the last invocation of candidateHosts */
    @volatile private var knownHostIds = Set.empty[UUID]

    /* The hosts that are deleted between the initial load of hosts, and the
     * moment when the request completes.
     */
    @volatile private var hostsDeletedDuringInit = Set.empty[UUID]

    /* Used to watch for deleted hosts, updates are not relevant */
    private val hostWatcher = new DeviceWatcher[Host](store,
                                                      scheduler,
                                                      onHostUpdate,
                                                      onHostDelete,
                                                      log = log)

    /* This is where we publish the ids of hosts as they get deleted */
    private val discardedSubject = PublishSubject.create[UUID]
    hostWatcher.subscribe()

    @inline
    private def isInitialized = hostsDeletedDuringInit eq null

    /** Observable that emits events as soon as a host is no longer eligible. In
      * the case of this `anywhere` selector, it will emit events when any host
      * is removed from ZOOM.
      */
    override val discardedHosts: Observable[UUID] = discardedSubject.asObservable()

    /** Current set of candidate hosts. In the case of this `anywhere`
      * scheduler, this is the whole list of hosts registered on ZOOM (either
      * dead or alive).
      *
      * @return a future that will complete with a set of ids of hosts
      *         present in storage.
      */
    override def candidateHosts: Future[Set[UUID]] = {
        if (isInitialized)
            return Promise.successful(knownHostIds).future

        store.getAll(classOf[Host])
             .recover {
                 case NonFatal(t) =>
                     log.info("Failed to retrieve host list", t)
                     List.empty[Host]
             }.map { _.map { _.getId.asJava }
                      .filterNot(hostsDeletedDuringInit.contains)
                      .toSet
             } andThen {
                 case _ => hostsDeletedDuringInit = null
             }
    }

    override def dispose(): Unit = {
        hostWatcher.unsubscribe()
        knownHostIds = Set.empty[UUID]
        hostsDeletedDuringInit = Set.empty[UUID]
    }

    private def onHostUpdate(host: Host): Unit = {
        knownHostIds += host.getId.asJava
    }

    private def onHostDelete(id: Object): Unit = id match {
        case id: Commons.UUID =>
            val hostId = id.asJava
            knownHostIds -= hostId
            if (hostsDeletedDuringInit != null) {
                hostsDeletedDuringInit += hostId
            }
            discardedSubject.onNext(hostId)
        case _ =>
    }
}
