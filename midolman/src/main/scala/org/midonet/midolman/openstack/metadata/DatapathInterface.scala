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

import com.google.inject.Injector
import org.slf4j.{Logger, LoggerFactory}
import org.midonet.packets.MAC

/*
 * Find the local port (OVSP_LOCAL) of the datapath ('midonet') and use it
 * to listen for metadata requests.
 *
 * NOTE(yamamoto): The use of the local port is merely for simplicity.
 * Plugging a dedicated port would be an alternative.
 */

class DatapathInterface(injector: Injector) {
    private val log: Logger = LoggerFactory getLogger classOf[Plumber]
    private val scanner = injector getInstance classOf[InterfaceScanner]

    def init = {
        val latch = new CountDownLatch(1)
        var port: InterfaceDescription = null

        val subscription =
                scanner.subscribe(new Observer[Set[InterfaceDescription]] {
            override def onCompleted() = {
                log debug "onCompleted"
            }
            override def onError(t: Throwable) = {
                log debug s"onError ${t}"
            }
            override def onNext(data: Set[InterfaceDescription]) = {
                log debug s"onNext ${data}"
                port = data.collectFirst{
                    case i: InterfaceDescription if i.getName == "midonet" => i
                }.get
                latch.countDown
            }
        })
        latch.await
        subscription.unsubscribe
        val mdInfo = ProxyInfo(0, MetadataApi.address,
                               MAC.bytesToString(port.getMac))
        val interface = "midonet"

        // XXX better to use rtnetlink
        Process(s"ip addr add ${MetadataApi.address}/16 dev ${interface}").!
        Process(s"ip link set ${interface} up").!

        log debug s"mdInfo ${mdInfo}"
        mdInfo
    }
}
