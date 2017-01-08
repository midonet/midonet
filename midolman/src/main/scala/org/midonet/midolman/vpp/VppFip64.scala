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

package org.midonet.midolman.vpp

import java.util.concurrent.atomic.AtomicBoolean

import rx.{Observer, Subscription}

import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.vpp.VppFip64.Notification
import org.midonet.midolman.vpp.VppProviderRouter.ProviderRouter
import org.midonet.midolman.vpp.VppUplink.{AddUplink, DeleteUplink}
import org.midonet.util.logging.Logger

object VppFip64 {

    /**
      * A trait for FIP64 notifications.
      */
    trait Notification

}

/**
  * A trait that monitors the FIP64 virtual topology, and emits notifications
  * via the current VPP executor.
  */
private[vpp] trait VppFip64 extends VppUplink
                                    with VppProviderRouter
                                    with VppExternalNetwork {

    this: VppExecutor =>

    protected def vt: VirtualTopology

    protected def log: Logger

    private val started = new AtomicBoolean(false)

    private val uplinkObserver = new Observer[Notification] {
        override def onNext(notification: Notification): Unit = {
            notification match {
                case AddUplink(portId, routerId, portAddress, uplinkPortIds) =>
                    addUplink(portId, routerId, uplinkPortIds)
                case DeleteUplink(portId) =>
                    removeUplink(portId)
            }
            send(notification)
        }

        override def onError(e: Throwable): Unit = {
            log.warn("Unexpected error on the uplink observable", e)
        }

        override def onCompleted(): Unit = {
            log warn "Uplink observable completed unexpectedly"
        }
    }
    @volatile private var uplinkSubscription: Subscription = _

    private val providerRouterObserver = new Observer[ProviderRouter] {
        override def onNext(router: ProviderRouter): Unit = {
            updateProviderRouter(router)
        }

        override def onError(e: Throwable): Unit = {
            log.warn("Unexpected error on the provider routers observable", e)
        }

        override def onCompleted(): Unit = {
            log warn "Provider routers observable completed unexpectedly"
        }
    }
    @volatile private var providerRouterSubscription: Subscription = _

    private val externalNetworkObserver = new Observer[Notification] {
        override def onNext(notification: Notification): Unit = {
            send(notification)
        }

        override def onError(e: Throwable): Unit = {
            log.warn("Unexpected error on the external networks observable", e)
        }

        override def onCompleted(): Unit = {
            log warn "External networks observable completed unexpectedly"
        }
    }
    @volatile private var externalNetworkSubscription: Subscription = _

    /**
      * Starts monitoring the FIP64 topology.
      */
    protected def startFip64(): Unit = {
        log debug s"Start monitoring FIP64 topology"
        if (started.compareAndSet(false, true)) {
            if (uplinkSubscription ne null) {
                uplinkSubscription.unsubscribe()
            }
            uplinkSubscription = uplinkObservable.subscribe(uplinkObserver)

            if (providerRouterSubscription ne null) {
                providerRouterSubscription.unsubscribe()
            }
            providerRouterSubscription =
                providerRouterObservable.subscribe(providerRouterObserver)

            if (externalNetworkSubscription ne null) {
                externalNetworkSubscription.unsubscribe()
            }
            externalNetworkSubscription =
                externalNetworkObservable.subscribe(externalNetworkObserver)

            startUplink()
        }
    }

    /**
      * Stops monitoring the FIP64 topology.
      */
    protected def stopFip64(): Unit = {
        log debug s"Stop monitoring FIP64 topology"
        if (started.compareAndSet(true, false)) {
            stopUplink()
            if (uplinkSubscription ne null) {
                uplinkSubscription.unsubscribe()
                uplinkSubscription = null
            }

            if (providerRouterSubscription ne null) {
                providerRouterSubscription.unsubscribe()
                providerRouterSubscription = null
            }

            if (externalNetworkSubscription ne null) {
                externalNetworkSubscription.unsubscribe()
                externalNetworkSubscription = null
            }
        }
    }

}
