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

import rx.Observable
import rx.subjects.PublishSubject

import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.services.MidonetBackend._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.containers.{ObjectTracker, Context}
import org.midonet.util.functors._

/** Processes notifications for active state of the specified port. The
  * class exposes an observable that emits [[PortEvent]] notifications
  * indicating when the port host, interface name or active state have changed.
  * The observable exposed by this class does not filter errors, and will
  * complete when the port is deleted.
  */
class PortTracker(val portId: UUID, context: Context)
    extends ObjectTracker[PortEvent] {

    private var currentPort: Port = null

    private val portStateSubject = PublishSubject.create[String]
    private var portStateReady: Boolean = false

    private val portObservable = context.store
        .observable(classOf[Port], portId)
        .distinctUntilChanged()
        .observeOn(context.scheduler)
        .doOnNext(makeAction1(portUpdated))
        .doOnCompleted(makeAction0 { mark.onCompleted() })
    private val portActiveObservable = context.stateStore
        .keyObservable(portStateSubject, classOf[Port], portId, ActiveKey)
        .observeOn(context.scheduler)
        .map[Boolean](makeFunc1(state => { portStateReady = true; state.nonEmpty }))

    override val observable: Observable[PortEvent] = Observable
        .combineLatest[Boolean, Port, PortEvent](
            portActiveObservable,
            portObservable,
            makeFunc2(buildEvent))
        .filter(makeFunc1(_ => isReady))
        .distinctUntilChanged()
        .takeUntil(mark)

    /** Indicates whether the current port state has received both the port
      * and port state data from storage.
      */
    override def isReady: Boolean = {
        (currentPort ne null) && portStateReady
    }

    /** Handles updates for the port. It emits the current host identifier
      * on the namespace subject in order to update the port state.
      */
    private def portUpdated(port: Port): Unit = {
        val hostId = if (port.hasHostId) port.getHostId.asJava else null

        context.log debug s"Port ${port.getId.asJava} updated: bound to " +
                          s"host $hostId"
        if ((currentPort eq null) || currentPort.getHostId.asJava != hostId) {
            portStateReady = false
            portStateSubject onNext hostId.asNullableString
        }
        currentPort = port
    }

    private def buildEvent(active: Boolean, port: Port): PortEvent = {
        ref = PortEvent(if (port.hasHostId) port.getHostId.asJava else null,
                        port.getInterfaceName,
                        active)
        context.log debug s"Built $ref"
        ref
    }
}