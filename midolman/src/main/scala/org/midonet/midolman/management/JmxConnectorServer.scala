/*
 * Copyright 2017 Midokura SARL
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

package org.midonet.midolman.management

import java.lang.management.ManagementFactory
import java.rmi.registry.{LocateRegistry, Registry}
import java.rmi.server.UnicastRemoteObject
import java.util

import javax.management.remote.{JMXConnectorServer, JMXConnectorServerFactory, JMXServiceURL}

import scala.util.control.NonFatal

import com.google.common.util.concurrent.AbstractService

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.util.logging.Logger

object JmxConnectorServer {

    val Log = Logger("org.midonet.midolman.management.jmx-server")

}

class JmxConnectorServer(config: MidolmanConfig) extends AbstractService {

    import JmxConnectorServer._

    private var registry: Registry = _

    private var server: JMXConnectorServer = _

    private val env = new util.HashMap[String, Object]()

    override def doStart(): Unit = {
        try {
            if (config.jmxConfig.enabled) {

                registry = LocateRegistry.createRegistry(config.jmxConfig.port)
                val mbs = ManagementFactory.getPlatformMBeanServer
                val url = new JMXServiceURL(
                    s"service:jmx:rmi:///jndi/rmi://:${config.jmxConfig.port}/jmxrmi")
                env.put("com.sun.management.jmxremote.local.only", "false")
                env.put("com.sun.management.jmxremote.ssl", "false")
                env.put("com.sun.management.jmxremote.authenticate", "false")

                server = JMXConnectorServerFactory
                    .newJMXConnectorServer(url, env, mbs)
                server.start()
                Log.info("JMX Connector server started.")
            } else {
                Log.info("JMX Connector server disabled.")
            }
        } catch {
            case NonFatal(e) =>
                Log.debug(s"Failed to start Jmx Connector Server, ignoring: " +
                          s"${e.getMessage}")
        }
        notifyStarted()
    }

    override def doStop(): Unit = {
        try {
            if (server ne null) {
                server.stop()
            }
            if (registry ne null) {
                UnicastRemoteObject.unexportObject(registry, true)
            }
            Log.info("JMX Connector server stopped.")
            notifyStopped()
        } catch {
            case NonFatal(e) =>
                Log.debug(s"Failed to stop Jmx Connector Server: ${e.getMessage}")
                notifyFailed(e)
        }
    }

}
