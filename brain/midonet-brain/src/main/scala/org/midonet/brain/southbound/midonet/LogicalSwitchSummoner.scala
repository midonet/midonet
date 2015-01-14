/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.brain.southbound.midonet

import java.util.UUID
import java.util.concurrent.Executors.newSingleThreadExecutor
import java.util.concurrent.{ConcurrentHashMap, ThreadFactory}

import org.slf4j.LoggerFactory
import rx.Observer
import rx.schedulers.Schedulers

import org.midonet.brain.southbound.vtep.VtepPeerPool
import org.midonet.cluster.EntityIdSetEvent.Type
import org.midonet.cluster.{DataClient, EntityIdSetEvent, EntityIdSetMonitor}
import org.midonet.midolman.state.{StateAccessException, ZookeeperConnectionWatcher}
import org.midonet.util.functors._

/** This is just a simple watcher that subscribers on a stream of updates
  * from MidoNet bridges, spots those that become part of a VxLan Gateway, and
  * starts the associated processes to manage the coordination with VTEPs. */
class LogicalSwitchSummoner(dataClient: DataClient,
                            zkConnWatcher: ZookeeperConnectionWatcher) {

    private val log = LoggerFactory.getLogger(classOf[LogicalSwitchSummoner])
    private val managers = new ConcurrentHashMap[UUID, LogicalSwitchManager]()

    private val executor = newSingleThreadExecutor(new ThreadFactory {
        override def newThread(r: Runnable): Thread = {
            val t = new Thread(r)
            t.setName("vxgw-bridge-creation-monitor")
            t
        }
    })

    // Confined to our single-threaded executor
    private var bridgeMonitor: EntityIdSetMonitor[UUID] = _

    private val vtepPeerPool = new VtepPeerPool()

    executor submit makeRunnable { resetMonitor() }

    /** Resets the monitor */
    private def resetMonitor(): Unit = {
        bridgeMonitor = dataClient.bridgesGetUuidSetMonitor(zkConnWatcher)
        bridgeMonitor.getObservable
            .observeOn(Schedulers.from(executor))
            .subscribe(new Observer[EntityIdSetEvent[UUID]] {
            override def onCompleted(): Unit = {
                log.warn("The bridges top level path was deleted!")
            }
            override def onError(e: Throwable): Unit = {
                log.warn("Error on network stream; restarting monitor", e)
                executor submit makeRunnable { resetMonitor() }
            }
            override def onNext(t: EntityIdSetEvent[UUID]): Unit = {
                if (t.`type` == Type.CREATE || t.`type` == Type.STATE) {
                    checkNetwork(t.value)
                }
            }
        })
    }

    /** Use after a network has either changed or been created to start a
      * manager for the associated VxLAN gateway if appropriate */
    private def checkNetwork(id: UUID): Unit = {
        try {
            val bridge = dataClient.bridgesGet(id)
            if (bridge.getVxLanPortIds != null ||
                bridge.getVxLanPortIds.isEmpty) {
                log.debug(s"Network $id is not bound to VTEPs, ignoring")
            }
        } catch {
            case e: StateAccessException =>
                zkConnWatcher.handleError("Start VxGW network watcher",
                                          makeRunnable { checkNetwork(id) }, e)
            case _: Throwable =>
                log.error(s"Error starting VxGW monitor for network $id")
        }
        val nw = new LogicalSwitchManager(id, dataClient, vtepPeerPool,
                                          zkConnWatcher,
                                          () => managers.remove(id))
        if (managers.putIfAbsent(id, nw) == null) {
            log.debug(s"Start vxgw manager for network $id")
            nw.start()
        } else {
            log.debug(s"Vxgw manager for network $id already exists")
        }

    }

}
