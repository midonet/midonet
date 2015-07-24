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
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{Executors, TimeUnit}

import scala.util.control.NonFatal

import org.slf4j.LoggerFactory
import rx.Observer

import org.midonet.cluster.DataClient
import org.midonet.cluster.services.vxgw.TunnelZoneState.FloodingProxyEvent

/**
 * This class implements an [[Observer]] that will subscribe to all the
 * tunnel zones exposed by the given [[TunnelZoneStatePublisher]], which
 * runs inside the cluster as part of the VxLAN Gateway Service.
 *
 * Whenever a tunnel zone emits a [[FloodingProxyEvent]], we will gossip the
 * result back to MidoNet by writing it to the NSDB, so that other MidoNet
 * components (typically the Agent) can use this information for their own
 * purposes.
 * 
 * Upon receiving a FP event, the snitch will log the tunnel zone that was
 * affected and queue an update.  A scheduler will process all updates,
 * reload the most recent flooding proxy for the tunnel zone, and try to
 * publish it to the NSDB.  Should this operation fail, the FP event will be
 * put back in the queue for a retry until the configured number of attempts
 * runs out.
 */
class FloodingProxySnitch(val tzState: TunnelZoneStatePublisher,
                          val dataClient: DataClient)
    extends Runnable with Observer[FloodingProxyEvent] {

    private val MAX_ATTEMPTS = 10
    private val log = LoggerFactory.getLogger(vxgwLog)

    private val pendingPublication = new AtomicReference(Map.empty[UUID, Int])

    private val executor = Executors.newSingleThreadScheduledExecutor()
    executor.scheduleAtFixedRate(this, 2, 2, TimeUnit.SECONDS)

    override def onCompleted(): Unit = {
        log.info("Tunnel zone update stream has closed, no more flooding" +
                 "proxy updates will be published to MidoNet")
    }
    override def onError(throwable: Throwable): Unit = {
        log.error("Tunnel zone update stream has closed with an error, no " +
                  "more flooding proxy updates will be published to MidoNet")
    }
    override def onNext(e: FloodingProxyEvent): Unit = {
        // Thread safe because Rx dictates that onNext must not be called
        // concurrently
        enqueue(e.tunnelZoneId)
    }

    private def enqueue(tzId: UUID, attempts: Int = MAX_ATTEMPTS): Unit = {
        pendingPublication.set(
            pendingPublication.get() += tzId -> attempts
        )
    }

    override def run(): Unit = {
        val current = pendingPublication.getAndSet(Map.empty)
        current.foreach { case (tzId, attempts) =>
            tzState.get(tzId) match {
                case null =>
                    log.info(s"Trying to publish flooding proxy of tunnel " +
                             s"zone $tzId to MidoNet, but the tunnel zone " +
                             s"seems to have been deleted just now")
                case thisTzState =>
                    val fp = thisTzState.getFloodingProxy
                    try {
                        if (fp != null) {
                            dataClient.setFloodingProxy(tzId, fp.id)
                            log.debug(s"Successfully announced host ${fp.id} " +
                                      "as Flooding Proxy of tunnel zone $tzId")
                        }
                    } catch {
                        case NonFatal(t) if attempts == 0 =>
                            log.warn("Failed to announce Flooding Proxy for " +
                                     s"tunnel zone $tzId, no retries left")
                        case NonFatal(t) =>
                            log.warn("Failed to announce Flooding Proxy for " +
                                     s"tunnel zone $tzId, I will retry.")
                            enqueue(tzId, attempts - 1)
                    }
            }
        }
    }
}
