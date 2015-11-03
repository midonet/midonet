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

package org.midonet.cluster.services.direct_connect

import java.util.UUID

import com.google.inject.Inject
import org.slf4j.LoggerFactory
import rx.subscriptions.CompositeSubscription
import rx.{Observable, Observer}

import org.midonet.cluster.models.Topology.Router
import org.midonet.cluster.services.{ClusterService, MidonetBackend, Minion}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.{ClusterConfig, ClusterNode}
import org.midonet.southbound.vtep.OvsdbVtepConnectionProvider

object DirectConnectService {
    val log = LoggerFactory.getLogger("org.midonet.cluster.direct_connect")
}

@ClusterService(name = "direct_connect")
class DirectConnectService @Inject()(
        nodeCtx: ClusterNode.Context,
        backend: MidonetBackend,
        ovsdbCnxnProvider: OvsdbVtepConnectionProvider,
        conf: ClusterConfig) extends Minion(nodeCtx) {

    import DirectConnectService.log

    private val mySubscriptions = new CompositeSubscription()

    private class RouterObserver extends Observer[Router] {

        @volatile private var id: UUID = null

        override def onCompleted(): Unit = {
            log.info(s"Router $id was deleted, stop watching.")
        }
        override def onError(t: Throwable): Unit = {
            log.warn(s"Error in Router $id update stream: ", t)
        }
        override def onNext(o: Router): Unit = {
            if (id == null) {
                id = o.getId.asJava
                log.info(s"Loaded initial state of router $id")
            } else {
                log.info(s"Router $id updated")
            }
        }
    }

    private val routersObserver = new Observer[Observable[Router]] {
        override def onCompleted(): Unit = {
            log.info("Completed stream of router updates (no more updates " +
                     "will be pushed to the VTEPs)")
        }
        override def onError(t: Throwable): Unit = {
            log.warn("Router update stream emits an error: ", t)
        }
        override def onNext(o: Observable[Router]): Unit = {
            mySubscriptions.add(o.subscribe(new RouterObserver()))
        }
    }

    override def isEnabled: Boolean = conf.directConnect.isEnabled

    override def doStop(): Unit = {
        log.debug("Stopping")
        mySubscriptions.unsubscribe()
        notifyStopped()
        log.debug("Stopped")
    }

    override def doStart(): Unit = {
        log.debug("Starting")

        mySubscriptions.add (
            backend.store.observable(classOf[Router]).subscribe(routersObserver)
        )

        notifyStarted()

        log.debug("Started")
    }

}
