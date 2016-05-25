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

package org.midonet.cluster.state

import java.util.UUID

import scala.util.control.NonFatal

import com.google.protobuf.TextFormat

import rx.Observable

import org.midonet.cluster.data.storage.{SingleValueKey, StateResult, StateStorage}
import org.midonet.cluster.models.State
import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.services.MidonetBackend.ActiveKey
import org.midonet.cluster.state.PortStateStorage.{PortActive, PortInactive, PortState}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.functors._

object PortStateStorage {

    final val UuidStringSize = 36

    trait PortState { def isActive: Boolean; }
    case class PortActive(hostId: UUID, tunnelKey: Option[Long]) extends PortState {
        override def isActive = true
    }
    case object PortInactive extends PortState {
        override def isActive = false
    }

    implicit def asPort(store: StateStorage): PortStateStorage = {
        new PortStateStorage(store)
    }

}

/**
 * A wrapper class around the [[StateStorage]] with utility methods for changing
 * the state information of a port.
 */
class PortStateStorage(val store: StateStorage) extends AnyVal {

    /**
     * Sets the port as active or inactive at the given host.
     */
    def setPortActive(portId: UUID, hostId: UUID, active: Boolean,
                      tunnelKey: Long = 0L)
    : Observable[StateResult] = {
        if (active) {
            val portState = State.PortState.newBuilder()
                                           .setHostId(hostId.asProto)
                                           .setTunnelKey(tunnelKey)
                                           .build()
            store.addValue(classOf[Port], portId, ActiveKey, portState.toString)
        } else {
            store.removeValue(classOf[Port], portId, ActiveKey, value = null)
        }
    }

    /**
      * Gets the state of the specified port and host.
      */
    def portState(portId: UUID, hostId: UUID): Observable[PortState] = {
        store.getKey(hostId.toString, classOf[Port], portId, ActiveKey)
             .map(makeFunc1 {
                 case SingleValueKey(_, Some(value), _) =>
                     parsePortActive(value)
                 case _ =>
                     PortInactive
             })
    }

    /**
      * An observable that emits notifications with the state of the specified
      * port at the last host emitted by `hostIds` observable.
      */
    def portStateObservable(portId: UUID, hostIds: Observable[UUID])
    : Observable[PortState] = {
        store.keyObservable(hostIds.map[String](makeFunc1 { _.asNullableString }),
                            classOf[Port], portId, ActiveKey) map makeFunc1 {
            case SingleValueKey(_, Some(value), _) =>
                parsePortActive(value)
            case _ =>
                PortInactive
        }
    }

    private def parsePortActive(value: String): PortState = {
        try {
            if (value.length == PortStateStorage.UuidStringSize) {
                PortActive(UUID.fromString(value), None)
            } else {
                val builder = State.PortState.newBuilder()
                TextFormat.merge(value, builder)
                val state = builder.build()
                PortActive(state.getHostId.asJava, Some(state.getTunnelKey))
            }
        } catch {
            case NonFatal(_) => PortInactive
        }
    }

}
