/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.midolman.topology

import java.util
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import com.google.common.util.concurrent.AbstractService

import rx.Subscriber

import org.midonet.cluster.data.storage.StateTable.Update
import org.midonet.cluster.data.storage.StateTableEncoder.GatewayHostEncoder.DefaultValue
import org.midonet.cluster.services.MidonetBackend
import org.midonet.midolman.logging.MidolmanLogging

object GatewayMappingService {

    /**
      * Returns an [[util.Enumeration]] with the host identifiers for the
      * current gateway hosts.
      */
    def gateways: util.Enumeration[UUID] = {
        self.gateways
    }

    @volatile private var self: GatewayMappingService = _

    /**
      * Registers a [[GatewayMappingService]] instance to this singleton.
      */
    private def register(service: GatewayMappingService): Unit = {
        self = service
    }
}

/**
  * A service that maintains the set of current gateways and their corresponding
  * tunnel information. The service observes the current gateways present in
  * NSDB via the gateways state table. For each gateway, the service loads their
  * tunnel zone information.
  */
class GatewayMappingService(vt: VirtualTopology)
    extends AbstractService with MidolmanLogging {

    import GatewayMappingService._

    override def logSource = "org.midonet.devices.gateway-mapping"

    private val gatewayMap = new ConcurrentHashMap[UUID, AnyRef]()
    private val gatewaySubscriber = new Subscriber[Update[UUID, AnyRef]] {
        override def onNext(update: Update[UUID, AnyRef]): Unit = {
            update match {
                case Update(hostId, null, _) =>
                    log debug s"Added gateway $hostId"
                    gatewayMap.put(hostId, DefaultValue)
                case Update(hostId, _, null) =>
                    log debug s"Removed gateway $hostId"
                    gatewayMap.remove(hostId)
                case _ =>
            }
        }

        override def onError(e: Throwable): Unit = {
            log.warn("Gateway state table error", e)
            gatewayMap.clear()
        }

        override def onCompleted(): Unit = {
            log.warn("Gateway state table observable completed unexpectedly")
            gatewayMap.clear()
        }
    }


    register(this)

    /**
      * Returns an [[util.Enumeration]] with the host identifiers for the
      * current gateway hosts.
      */
    def gateways: util.Enumeration[UUID] = {
        gatewayMap.keys()
    }

    override protected def doStart(): Unit = {
        log info "Starting gateway mapping service"
        vt.stateTables.getTable[UUID, AnyRef](MidonetBackend.GatewayTable)
            .observable
            .subscribe(gatewaySubscriber)

        notifyStarted()
    }

    override protected def doStop(): Unit = {
        log info "Stopping gateway mapping service"
        gatewaySubscriber.unsubscribe()
        notifyStopped()
    }

}
