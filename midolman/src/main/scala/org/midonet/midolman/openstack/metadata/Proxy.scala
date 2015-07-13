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

import org.eclipse.jetty.server.Server
import org.slf4j.{Logger, LoggerFactory}
import scala.util.control.NonFatal
import java.net.InetSocketAddress
import java.net.InetAddress

object Proxy {
    private val log: Logger = LoggerFactory getLogger Proxy.getClass
    private val ip = InetAddress getByName "169.254.169.254"
    private val port = 80
    private var server: Server = _

    def start = {
        val sa = new InetSocketAddress(ip, port)
        log info s"Starting metadata proxy on ${sa}"
        val s = new Server(sa)
        s.setHandler(new ProxyHandler)
        try {
            s.start
            server = s
        } catch {
            case NonFatal(e) =>
                log error s"Failed to start metadata proxy: ${e}"
        }
    }

    def stop = {
        if (server != null) {
            log info s"Stopping metadata proxy"
            server.stop
            server.join
            server = null
        }
    }
}
