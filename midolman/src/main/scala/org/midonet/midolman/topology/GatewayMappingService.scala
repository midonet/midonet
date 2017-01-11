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
import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.{Future, Promise}

import com.google.common.util.concurrent.AbstractService

import rx.Observer
import rx.subscriptions.CompositeSubscription

import org.midonet.cluster.data.storage.StateTable.Update
import org.midonet.cluster.data.storage.{StateTable, StorageException}
import org.midonet.cluster.models.Neutron.NeutronNetwork
import org.midonet.cluster.services.MidonetBackend
import org.midonet.midolman.NotYetException
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.simulation.{BridgePort, Port}
import org.midonet.util.logging.Logger

object GatewayMappingService {

    /**
      * Tries to return a gateway for the specified FIP64 downlink port, if one
      * is already cached. If the virtual topology is not yet loaded, the
      * method throws a [[NotYetException]] with a future that will complete
      * either when the list of gateways becomes available or when the
      * operation completes with an error.
      */
    @throws[NotYetException]
    def tryGetGateway(portId: UUID, hash: Int): UUID = {
        self.tryGetGateway(portId, hash: Int)
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
        @volatile private var snapshot: Array[UUID] = _
        private val terminated = new AtomicBoolean()

        private val updateObserver = new Observer[Update[UUID, AnyRef]] {
            override def onNext(update: Update[UUID, AnyRef]): Unit = {
                log debug s"Network $networkId gateway table updated"
                snapshot = table.localSnapshot.keySet.toArray
            }

            override def onCompleted(): Unit = {
                log debug s"Network $networkId gateway table completed"
                stop(new StorageException(s"Network $networkId deleted"),
                     callback = true)
            }

            override def onError(e: Throwable): Unit = {
                log.warn(s"Network $networkId gateway table error", e)
                stop(e, callback = true)
            }
        }
        private val readyObserver = new Observer[StateTable.Key] {
            override def onNext(t: StateTable.Key): Unit = {
                log debug s"Network $networkId gateway table ready"
                if (snapshot eq null)
                    snapshot = table.localSnapshot.keySet.toArray
                if (!future.isCompleted)
                    promise.trySuccess(Unit)
            }

            override def onCompleted(): Unit = {
                log debug s"Network $networkId gateway table ready completed"
                stop(new StorageException(s"Network $networkId deleted"),
                     callback = true)
            }

            override def onError(e: Throwable): Unit = {
                log.warn(s"Network $networkId gateway table ready error", e)
                stop(e, callback = true)
            }
        }
        private val subscription = new CompositeSubscription()

        /**
          * Starts monitoring the network's gateway table. The state monitors
          * both the table updates and the table ready observable, in case the
          * table is empty and the update observable does not emit a
          * notification.
          */
        def start(): Unit = {
            log debug s"Start montoring gateway for network $networkId"
            subscription add table.observable.subscribe(updateObserver)
            subscription add table.ready.subscribe(readyObserver)
        }

        /**
          * Stops monitoring the network's gateway table. If the table has
          * not yet been loaded, the future exposed by this [[GatewayState]]
          * will complete with an [[IllegalStateException]].
          */
        def stop(): Unit = {
            log debug s"Stop montoring gateway for network $networkId"
            stop(new IllegalStateException("Gateway mapping service stopping"),
                 callback = false)
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
        def tryGet(hash: Int): UUID = {
            log debug s"Try get gateway for network $networkId with hash $hash"
            val s = snapshot
            if (s ne null) {
                if (s.length == 0) null
                else s(hash % s.length)
            } else {
                throw NotYetException(future,
                                      s"Gateways for network $networkId not yet " +
                                      "available")
            }
        }

        /**
          * Stops the current state and completes the exposed future with the
          * given [[Throwable]] if not already completed.
          */
        private def stop(e: Throwable, callback: Boolean): Unit = {
            if (terminated.compareAndSet(false, true)) {
                table.stop()
                subscription.unsubscribe()
                if (!future.isCompleted) {
                    promise.tryFailure(e)
                }
                if (callback) close(this)
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
    def tryGetGateway(portId: UUID, hash: Int): UUID = {
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
        }
        gatewayState.tryGet(hash)
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
