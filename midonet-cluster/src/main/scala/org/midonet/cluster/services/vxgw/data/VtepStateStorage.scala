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

package org.midonet.cluster.services.vxgw.data

import java.util.UUID

import scala.collection.JavaConverters._

import com.google.protobuf.TextFormat

import rx.Observable

import org.midonet.cluster.data.storage.{SingleValueKey, StateResult, StateStorage, StorageException}
import org.midonet.cluster.models.State.{VtepConfiguration, VtepConnectionState}
import org.midonet.cluster.models.Topology.Vtep
import org.midonet.cluster.services.MidonetBackend.{VtepConnState, VtepConfig}
import org.midonet.cluster.services.vxgw.data.VtepStateStorage._
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.packets.IPAddr
import org.midonet.util.functors._

object VtepStateStorage {

    implicit def asVtepStateStorage(storage: StateStorage): VtepStateStorage = {
        new VtepStateStorage(storage)
    }

    /** Serializes the VTEP configuration for storing it in the state storage. */
    @inline private def serializeConfig(config: VtepConfiguration): String = {
        config.toString
    }

    /** Deserializes the VTEP configuration from the format used in the state
      * storage. */
    @inline private def deserializeConfig(string: String): VtepConfiguration = {
        val builder = VtepConfiguration.newBuilder()
        TextFormat.merge(string, builder)
        builder.build()
    }

}

/**
 * A wrapper class around the [[StateStorage]] with utility methods for reading
 * and writing the VTEP configuration and connection state.
 */
class VtepStateStorage(val store: StateStorage) extends AnyVal {

    /** Gets the configuration for the specified VTEP from the state store. The
      * method returns an observable, which when subscribed to, emits one
      * notification with the VTEP configuration. */
    def getVtepConfig(vtepId: UUID): Observable[VtepConfiguration] = {
        store.getKey(classOf[Vtep], vtepId, VtepConfig) map makeFunc1 {
            case SingleValueKey(_, Some(config), _) =>
                deserializeConfig(config)
            case SingleValueKey(_, None, _) =>
                VtepConfiguration.getDefaultInstance
            case _ => throw new StorageException(
                s"Failed to read state configuration for VTEP $vtepId")
        }
    }

    /** Gets the connection state for the specified VTEP from the state store.
      * The method returns an observable, which when subscribed to, emits one
      * notification with the VTEP connection state. */
    def getVtepConnectionState(vtepId: UUID): Observable[VtepConnectionState] = {
        store.getKey(classOf[Vtep], vtepId, VtepConnState) map makeFunc1 {
            case SingleValueKey(_, Some(state), _) =>
                VtepConnectionState.valueOf(state)
            case SingleValueKey(_, None, _) =>
                VtepConnectionState.VTEP_DISCONNECTED
            case _ =>
                VtepConnectionState.VTEP_ERROR
        }
    }

    /** Sets the VTEP configuration. The method returns an observable, which when
      * subscribed to writes the VTEP configuration to the state store and emits
      * a single notification with the result of the operation. */
    def setVtepConfig(vtepId: UUID, config: VtepConfiguration)
    : Observable[StateResult] = {
        store.addValue(classOf[Vtep], vtepId, VtepConfig, serializeConfig(config))
    }

    /** Sets the VTEP configuration. The method returns an observable, which when
      * subscribed to writes the VTEP configuration to the state store and emits
      * a single notification with the result of the operation. */
    def setVtepConfig(vtepId: UUID, name: String, description: String,
                    tunnelAddresses: Seq[IPAddr]): Observable[StateResult] = {
        val config = VtepConfiguration.newBuilder()
                                      .setName(name)
                                      .setDescription(description)
                                      .addAllTunnelAddresses(tunnelAddresses
                                                         .map(_.asProto).asJava)
                                      .build()
        setVtepConfig(vtepId, config)
    }

    /** Sets the VTEP connection state. The method returns an observable, which
      * when subscribed to writes the VTEP configuration to the state store and
      * emits a single notification with the result of the operation. */
    def setVtepConnectionState(vtepId: UUID, state: VtepConnectionState)
    : Observable[StateResult] = {
        store.addValue(classOf[Vtep], vtepId, VtepConnState, state.toString)
    }

    /** An observable that emits notifications when the configuration of the
      * specified VTEP changes. */
    def vtepConfigObservable(vtepId: UUID): Observable[VtepConfiguration] = {
        store.keyObservable(classOf[Vtep], vtepId, VtepConfig) map makeFunc1 {
            case SingleValueKey(_, Some(config), _) =>
                deserializeConfig(config)
            case SingleValueKey(_, None, _) =>
                VtepConfiguration.getDefaultInstance
            case _ => throw new StorageException(
                s"Failed to read state configuration for VTEP $vtepId")
        }
    }

    /** An observable that emits notifications when the connection state of the
      * specified VTEP changes. */
    def vtepConnectionStateObservable(vtepId: UUID): Observable[VtepConnectionState] = {
        store.keyObservable(classOf[Vtep], vtepId, VtepConnState) map makeFunc1 {
            case SingleValueKey(_, Some(state), _) =>
                VtepConnectionState.valueOf(state)
            case SingleValueKey(_, None, _) =>
                VtepConnectionState.VTEP_DISCONNECTED
            case _ =>
                VtepConnectionState.VTEP_ERROR
        }
    }

}
