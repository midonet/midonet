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

import java.util.concurrent.CountDownLatch
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.host.scanner.InterfaceScanner
import scala.sys.process.Process
import rx.Observer
import rx.Observable
import rx.subjects.ReplaySubject

import scala.Option.option2Iterable
import scala.collection.JavaConversions.asJavaIterable

import org.slf4j.{Logger, LoggerFactory}
import org.midonet.packets.MAC
import org.midonet.util.functors.makeFunc1

/*
 * Find the local port (OVSP_LOCAL) of the datapath ('midonet') and use it
 * to listen for metadata requests.
 *
 * NOTE(yamamoto): The use of the local port is merely for simplicity.
 * Plugging a dedicated port would be an alternative.
 */

class DatapathInterface (private val scanner: InterfaceScanner) {
    private val log: Logger = MetadataService.getLogger

    def init = {
        val interface = "midonet"
        val obs = ReplaySubject.create[Set[InterfaceDescription]]
        val subscription = scanner.subscribe(obs)
        val portObs = obs flatMap makeFunc1 { data =>
            Observable.from(asJavaIterable(data.collectFirst {
                case i if i.getName == interface => i
            }))
        }
        val port = portObs.toBlocking.first
        subscription.unsubscribe
        val mdInfo = ProxyInfo(0, MetadataApi.address,
                               MAC.bytesToString(port.getMac))

        // XXX better to use rtnetlink
        Process(s"ip addr add ${MetadataApi.address}/16 dev ${interface}").!
        Process(s"ip link set ${interface} up").!

        log debug s"mdInfo ${mdInfo}"
        mdInfo
    }
}
