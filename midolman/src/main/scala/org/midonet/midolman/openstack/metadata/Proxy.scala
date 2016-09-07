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

import java.net.{InetAddress, InetSocketAddress}

import scala.util.control.NonFatal

import org.eclipse.jetty.server.Server

import org.midonet.midolman.config.MidolmanConfig

/*
 * Metadata proxy server; a http proxy running on the hypervisor.
 * Listens on 169.254.169.254:9697 and forwards requests to Nova Metadata Api.
 */

object Proxy {
    final val Ip = InetAddress getByName MetadataApi.Address
    final val Port = 9697  // REVISIT(yamamoto): should be a config?
    private var server: Server = _

    def start(config: MidolmanConfig): Unit = {
        val sa = new InetSocketAddress(Ip, port)
        Log info s"Starting metadata proxy on $sa"
        val s = new Server(sa)
        s.setHandler(new ProxyHandler(config))
        try {
            s.start()
            server = s
        } catch {
            case NonFatal(e) =>
                Log.error("Failed to start metadata proxy", e)
        }
    }

    def stop(): Unit = {
        if (server != null) {
            Log info s"Stopping metadata proxy"
            server.stop()
            server.join()
            server = null
        }
    }
}
