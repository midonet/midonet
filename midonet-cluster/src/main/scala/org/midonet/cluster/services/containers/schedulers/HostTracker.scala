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

import scala.util.control.NonFatal

import com.google.protobuf.TextFormat

import org.midonet.cluster.data.storage.{SingleValueKey, StateKey}
import org.midonet.cluster.models.State.ContainerServiceStatus
import org.midonet.cluster.models.Topology.Host
import org.midonet.cluster.services.MidonetBackend._
import org.midonet.cluster.services.containers.ContainerService
import org.midonet.containers.{Context, ObjectTracker}
import org.midonet.util.functors._

/** Processes notifications for the container service running on the
  * specified host. The class returns an observable, which emits
  * notifications when the running state of the container service on
  * the host has changed. The observable completes either when the host
  * is deleted, or when the `complete` method is called indicating that the
  * host is no longer part of the host group.
  */
class HostTracker(hostId: UUID, context: Context)
    extends ObjectTracker[HostEvent] {

    override val observable = context.stateStore
        .keyObservable(hostId.toString, classOf[Host], hostId, ContainerKey)
        .onBackpressureBuffer(ContainerService.SchedulingBufferSize)
        .observeOn(context.scheduler)
        .map[HostEvent](makeFunc1(buildEvent))
        .distinctUntilChanged()
        .onErrorReturn(makeFunc1(_ => HostEvent(running = false)))
        .takeUntil(mark)

    override def isReady = ref ne null

    /** Processes updates from the container service state running on the
      * current host, and return this state.
      */
    private def buildEvent(stateKey: StateKey): HostEvent = {
        ref = stateKey match {
            case SingleValueKey(_, Some(value), _) =>
                try {
                    val builder = ContainerServiceStatus.newBuilder()
                    TextFormat.merge(value, builder)
                    HostEvent(running = true, builder.build())
                } catch {
                    case NonFatal(e) =>
                        HostEvent(running = false)
                }
            case _ => HostEvent(running = false)
        }
        ref
    }
}
