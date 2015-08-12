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
import org.midonet.cluster.models.State.{VtepConnectionState, VtepInformation}
import org.midonet.cluster.models.Topology.Vtep
import org.midonet.cluster.services.MidonetBackend.{VtepConnState, VtepInfo}
import org.midonet.cluster.services.vxgw.data.VtepStateStorage._
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.packets.IPAddr
import org.midonet.util.functors._

object VtepStateStorage {

    implicit def asVtepStateStorage(storage: StateStorage): VtepStateStorage = {
        new VtepStateStorage(storage)
    }

    /** Serializes the VTEP information for storing it in the state storage. */
    @inline private def serializeInfo(info: VtepInformation): String = {
        info.toString
    }

    /** Deserializes the VTEP information from the format used in the state
      * storage. */
    @inline private def deserializeInfo(string: String): VtepInformation = {
        val builder = VtepInformation.newBuilder()
        TextFormat.merge(string, builder)
        builder.build()
    }

}

/**
 * A wrapper class around the [[StateStorage]] with utility methods for reading
 * and writing the VTEP information and connection state.
 */
class VtepStateStorage(val store: StateStorage) extends AnyVal {

    /** Gets the information for the specified VTEP from the state store. The
      * method returns an observable, which when subscribed to, emits one
      * notification with the VTEP information. */
    def getVtepInfo(vtepId: UUID): Observable[VtepInformation] = {
        store.getKey(classOf[Vtep], vtepId, VtepInfo) map makeFunc1 {
            case SingleValueKey(_, Some(info), _) => deserializeInfo(info)
            case SingleValueKey(_, None, _) => VtepInformation.getDefaultInstance
            case _ => throw new StorageException(
                s"Failed to read state information for VTEP $vtepId")
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

    /** Sets the VTEP information. The method returns an observable, which when
      * subscribed to writes the VTEP information to the state store and emits
      * a single notification with the result of the operation. */
    def setVtepInfo(vtepId: UUID, info: VtepInformation): Observable[StateResult] = {
        store.addValue(classOf[Vtep], vtepId, VtepInfo, serializeInfo(info))
    }

    /** Sets the VTEP information. The method returns an observable, which when
      * subscribed to writes the VTEP information to the state store and emits
      * a single notification with the result of the operation. */
    def setVtepInfo(vtepId: UUID, name: String, description: String,
                    tunnelAddresses: Seq[IPAddr]): Observable[StateResult] = {
        val info = VtepInformation.newBuilder()
                                  .setName(name)
                                  .setDescription(description)
                                  .addAllTunnelAddresses(tunnelAddresses
                                                         .map(_.asProto).asJava)
                                  .build()
        setVtepInfo(vtepId, info)
    }

    /** Sets the VTEP name. The method returns an observable, which when
      * subscribed to writes the VTEP information to the state store and emits
      * a single notification with the result of the operation. */
    def setVtepName(vtepId: UUID, name: String): Observable[StateResult] = {
        getVtepInfo(vtepId) flatMap makeFunc1 { info =>
            setVtepInfo(vtepId, info.toBuilder.setName(name).build())
        }
    }

    /** Sets the VTEP description. The method returns an observable, which when
      * subscribed to writes the VTEP information to the state store and emits
      * a single notification with the result of the operation. */
    def setVtepDescription(vtepId: UUID, description: String)
    : Observable[StateResult] = {
        getVtepInfo(vtepId) flatMap makeFunc1 { info =>
            setVtepInfo(vtepId, info.toBuilder.setDescription(description).build())
        }
    }

    /** Sets the VTEP tunnel addresses. The method returns an observable, which
      * when subscribed to writes the VTEP information to the state store and
      * emits a single notification with the result of the operation. */
    def setVtepTunnelAddresses(vtepId: UUID, tunnelAddresses: Seq[IPAddr])
    : Observable[StateResult] = {
        getVtepInfo(vtepId) flatMap makeFunc1 { info =>
            setVtepInfo(vtepId,
                        info.toBuilder
                            .clearTunnelAddresses()
                            .addAllTunnelAddresses(tunnelAddresses
                                                       .map(_.asProto).asJava)
                            .build())
        }
    }

    /** Sets the VTEP connection state. The method returns an observable, which
      * when subscribed to writes the VTEP information to the state store and
      * emits a single notification with the result of the operation. */
    def setVtepConnectionState(vtepId: UUID, state: VtepConnectionState)
    : Observable[StateResult] = {
        store.addValue(classOf[Vtep], vtepId, VtepConnState, state.toString)
    }

    /** An observable that emits notifications when the information of the
      * specified VTEP changes. */
    def vtepInfoObservable(vtepId: UUID): Observable[VtepInformation] = {
        store.keyObservable(classOf[Vtep], vtepId, VtepInfo) map makeFunc1 {
            case SingleValueKey(_, Some(info), _) => deserializeInfo(info)
            case SingleValueKey(_, None, _) => VtepInformation.getDefaultInstance
            case _ => throw new StorageException(
                s"Failed to read state information for VTEP $vtepId")
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
