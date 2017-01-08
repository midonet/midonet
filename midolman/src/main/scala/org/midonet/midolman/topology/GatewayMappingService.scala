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

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import scala.concurrent.{Future, Promise}
import scala.util.Random

import com.google.common.util.concurrent.AbstractService

import rx.{Observer, Subscription}

import org.midonet.cluster.data.storage.{StateTable, StorageException}
import org.midonet.cluster.models.Neutron.NeutronNetwork
import org.midonet.cluster.services.MidonetBackend
import org.midonet.midolman.NotYetException
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.simulation.{BridgePort, Port}
import org.midonet.util.logging.Logger

object GatewayMappingService {

    private final val random = new Random()

    /**
      * Tries to return a gateway for the specified FIP64 downlink port, if one
      * is already cached. If the virtual topology is not yet loaded, the
      * method throws a [[NotYetException]] with a future that will complete
      * either when the list of gateways becomes available or when the
      * operation completes with an error.
      */
    @throws[NotYetException]
    def tryGetGateway(portId: UUID): UUID = {
        self.tryGetGateway(portId)
    }

    @volatile private var self: GatewayMappingService = _

    /**
      * Registers a [[GatewayMappingService]] instance to this singleton.
      */
    private def register(service: GatewayMappingService): Unit = {
        self = service
    }

    /**
      * Maintains the state for a given Neutron network by monitoring the
      * network's gateway table and caching its content.
      */
    private class GatewayState(networkId: UUID, vt: VirtualTopology, log: Logger)
                              (close: (GatewayState) => Unit) {

        private val promise = Promise[AnyRef]()
        private val table =
            vt.stateTables.getTable[UUID, AnyRef](classOf[NeutronNetwork],
                                                  networkId,
                                                  MidonetBackend.GatewayTable)
        private val observer = new Observer[StateTable.Key] {
            override def onNext(t: StateTable.Key): Unit = {
                if (table.isReady && !future.isCompleted) {
                    promise.trySuccess(Unit)
                }
            }

            override def onCompleted(): Unit = {
                table.stop()
                fail(new StorageException(s"Network $networkId deleted"))
                close(GatewayState.this)
            }

            override def onError(e: Throwable): Unit = {
                table.stop()
                fail(e)
                close(GatewayState.this)
            }
        }
        @volatile private var subscription: Subscription = _

        /**
          * Starts monitoring the network's gateway table.
          */
        def start(): Unit = {
            subscription = table.ready.subscribe(observer)
        }

        /**
          * Stops monitoring the network's gateway table. If the table has
          * not yet been loaded, the future exposed by this [[GatewayState]]
          * will complete with an [[IllegalStateException]].
          */
        def stop(): Unit = {
            table.stop()
            fail(new IllegalStateException("Gateway mapping service stopping"))
        }

        /**
          * A future that completes with success when the gateway table
          * becomes available, or with a failure if loading the table fails.
          */
        def future: Future[AnyRef] = promise.future

        /**
          * Returns a gateway for the current Neutron network or throws a
          * [[NotYetException]] if the topology is not yet avaiable.
          */
        @throws[NotYetException]
        def tryGet: UUID = {
            if (table.isReady) {
                val snapshot = table.localSnapshot.keySet
                if (snapshot.isEmpty) null
                else {
                    val hostIds = snapshot.toArray
                    hostIds(random.nextInt(hostIds.length))
                }
            } else {
                throw NotYetException(future,
                                      s"Gateways for network $networkId not yet " +
                                      "available")
            }
        }

        /**
          * Fails the exposed future with the given [[Throwable]].
          */
        private def fail(e: Throwable): Unit = {
            val sub = subscription
            if (sub ne null) {
                sub.unsubscribe()
            }
            if (!future.isCompleted) {
                promise.tryFailure(e)
            }
        }
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

    private val gateways = new ConcurrentHashMap[UUID, GatewayState]()

    register(this)

    @throws[NotYetException]
    def tryGetGateway(portId: UUID): UUID = {
        val routerPort = vt.tryGet(classOf[Port], portId)
        if (routerPort.peerId eq null) {
            return null
        }
        val peerPort = vt.tryGet(classOf[Port], routerPort.peerId)
        if (!peerPort.isInstanceOf[BridgePort]) {
            return null
        }
        val networkId = peerPort.deviceId
        var gatewayState = gateways.get(networkId)
        if (gatewayState eq null) {
            gatewayState = new GatewayState(networkId, vt, log) ({
                gateways.remove(networkId, _)
            })
            val state = gateways.putIfAbsent(peerPort.deviceId, gatewayState)
            if (state eq null) {
                gatewayState.start()
            } else {
                gatewayState = state
            }
            throw NotYetException(gatewayState.future,
                                  s"Gateways for network $networkId not yet " +
                                  "available")
        }
        gatewayState.tryGet
    }

    override protected def doStart(): Unit = {
        log info "Starting gateway mapping service"
        notifyStarted()
    }

    override protected def doStop(): Unit = {
        log info "Stopping gateway mapping service"
        val iterator = gateways.entrySet().iterator()
        while (iterator.hasNext) {
            iterator.next().getValue.stop()
        }
        notifyStopped()
    }

}
