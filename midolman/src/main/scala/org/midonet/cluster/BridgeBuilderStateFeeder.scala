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
package org.midonet.cluster

import java.util.UUID
import javax.inject.Named

import com.google.inject.Inject
import org.midonet.cluster.client.{BridgeBuilder, IpMacMap, MacLearningTable}
import org.midonet.midolman.state._
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.eventloop.Reactor
import org.midonet.util.functors.Callback3
import org.slf4j.{Logger, LoggerFactory}

/**
  * This class constructs various state objects owned by the bridge and feeds
  * them to the corresponding BridgeBuilder.
  */
class BridgeBuilderStateFeeder {

    @Inject
    var dataClient: DataClient = _

    @Inject
    var connWatcher: ZkConnectionAwareWatcher = _

    @Inject // the same reactor as ZkDirectory
    @Named("directoryReactor") // ZKConnectionProvider.DIRECTORY_REACTOR_TAG)
    var reactor: Reactor = _   // for some reason the constant doesn't work

    /** An implementation of a repliated mac learning table */
    class MacLearningTableImpl(val map: MacPortMap,
                               val bridgeId: UUID,
                               val vlanId: Short,
                               val reactor: Reactor) extends MacLearningTable {

        private val log: Logger =
            LoggerFactory.getLogger(classOf[MacLearningTableImpl])

        /* It's ok to do a synchronous get on the map because it only queries
         * local state (doesn't go remote like the other calls. */
        @Override
        def get(mac: MAC): UUID = map.get(mac)

        private def onAdd(mac: MAC, portId: UUID) = new Runnable() {
            override def run(): Unit = {
                try {
                    map.put(mac, portId)
                } catch { case e: Throwable =>
                    log.error(s"Failed add $mac, VLAN $vlanId, port $portId", e)
                }
                log.info("Added {}, VLAN {} to port {} for bridge {}",
                         Array(mac, vlanId, portId, bridgeId))
            }
        }

        private def onRemove(mac: MAC, portId: UUID) = new Runnable() {
            override def run() {
                try {
                    map.removeIfOwnerAndValue(mac, portId)
                } catch { case e: Throwable =>
                    log.error(s"Failed remove $mac, VLAN $vlanId port " +
                              s"$portId on bridge $bridgeId", e)
                }
            }
        }

        override def add(mac: MAC, portId: UUID): Unit =
            reactor.submit(onAdd(mac, portId))

        override def remove(mac: MAC, portId: UUID): Unit =
            reactor.submit(onRemove(mac, portId))

        /* This notify() registers its callback directly with the underlying
         * MacPortMap map, so the callbacks are called from MacPortMap context
         * and should perform ActorRef::tell or such to switch to the context
         * appropriate for the callback's work. */
        override def notify(cb: Callback3[MAC, UUID, UUID]): Unit = {
            reactor.submit(new Runnable() {
                override def run() {
                    map.addWatcher(new ReplicatedMap.Watcher[MAC, UUID]() {
                        override def processChange(key: MAC, oldId: UUID,
                                                   newId: UUID) {
                            cb.call(key, oldId, newId)
                        }
                    })
                }
            })
        }
    }

    class OnUpdate(vlanId: Short, builder: BridgeBuilder)
        extends Callback3[MAC, UUID, UUID] {
        override def call(mac: MAC, oldPort: UUID, newPort: UUID): Unit =
            builder.updateMacEntry(vlanId, mac, oldPort, newPort)
    }

    /** Provides a replicated MAC learning table that will notify the given
      * Builder whenever there is an update. */
    @throws[StateAccessException]
    def feedLearningTable(toBuilder: BridgeBuilder, bridgeId: UUID, vlanId: Short)
    : MacLearningTable = {
        val map = dataClient.bridgeGetMacTable(bridgeId, vlanId, true)
        map.setConnectionWatcher(connWatcher)
        map.start()
        val table = new MacLearningTableImpl(map, bridgeId, vlanId, reactor)
        toBuilder.setMacLearningTable(vlanId, table)
        table.notify(new OnUpdate(vlanId, toBuilder))
        table
    }

    /** Feeds the given builder with an IP to mac table */
    @throws[StateAccessException]
    def feedIpToMacMap(toBuilder: BridgeBuilder, bridgeId: UUID) {
        val ip4MacMap = dataClient.getIp4MacMap(bridgeId)
        ip4MacMap.setConnectionWatcher(connWatcher)
        ip4MacMap.start()
        toBuilder.setIp4MacMap(new IpMacMap[IPv4Addr]() {
            /* Sync get are ok because it only queries local state, unlike
             * the other calls in the map */
            override def get(ip: IPv4Addr): MAC = ip4MacMap.get(ip)
        })
    }

}

