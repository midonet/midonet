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

package org.midonet.midolman.openstack.metadata

import scala.Option.option2Iterable
import scala.collection.JavaConversions.asJavaIterable
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.sys.process.Process

import akka.actor.ActorSystem

import rx.Observable
import rx.subjects.BehaviorSubject

import org.midonet.midolman.DatapathState
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.midolman.io.{UpcallDatapathConnectionManager, VirtualMachine}
import org.midonet.odp.ports.InternalPort
import org.midonet.packets.MAC
import org.midonet.util.concurrent.CallingThreadExecutionContext
import org.midonet.util.functors.makeFunc1

class DatapathInterface(private val scanner: InterfaceScanner,
                        private val dpState: DatapathState,
                        private val dpConnManager:
                            UpcallDatapathConnectionManager) {

    private def run(command: String) = {
        if (Process(command).! != 0) {
            throw new RuntimeException(s"Command failed: $command")
        }
    }

    def init(implicit as: ActorSystem) = {
        /*
         * 1. Subscribe InterfaceScanner
         * 2. Create our port
         * 3. Wait for InterfaceDescription for the port
         */
        implicit val ec = CallingThreadExecutionContext
        val ifName = "metadata"
        val obs = BehaviorSubject.create[Set[InterfaceDescription]]
        val subscription = scanner.subscribe(obs)
        val create = dpConnManager.createAndHookDpPort(dpState.datapath,
                                                       new InternalPort(ifName),
                                                       VirtualMachine)
        /*
         * REVISIT(yamamoto): Consider creating a virtual port and a binding
         * to the DatapathController here.  (not persistently on ZooKeeper)
         */
        val (dpPort, _) = Await.result(create, Duration.Inf)
        val portObs = obs flatMap makeFunc1 { data =>
            Observable.from(asJavaIterable(data.collectFirst {
                case i if i.getName == ifName => i
            }))
        }
        val port = portObs.toBlocking.first
        subscription.unsubscribe()
        val mdInfo = ProxyInfo(dpPort.getPortNo, MetadataApi.Address,
                               MAC.bytesToString(port.getMac.getAddress))

        /*
         * Assign the IP address which our metadata proxy will listen on.
         * Make the port up.
         *
         * REVISIT(yamamoto): better to use rtnetlink
         */
        run(s"ip addr add ${MetadataApi.Address}/16 dev $ifName")
        run(s"ip link set $ifName up")

        Log debug s"mdInfo $mdInfo"
        mdInfo
    }
}
