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
import java.rmi.registry.LocateRegistry

import javax.management.remote.{JMXConnectorServer, JMXConnectorServerFactory, JMXServiceURL}

import scala.util.control.NonFatal

import com.google.common.util.concurrent.AbstractService

import org.midonet.util.logging.Logger

object JmxConnectorServer {

    val Log = Logger("org.midonet.midolman.management.jmx-server")

}

class JmxConnectorServer extends AbstractService {

    import JmxConnectorServer._

    private val jmxPort = System.getenv("JMX_PORT").toInt

    private var server: JMXConnectorServer = _

    override def doStart(): Unit = {
        try {
            LocateRegistry.createRegistry(jmxPort)
            val mbs = ManagementFactory.getPlatformMBeanServer
            val url = new JMXServiceURL(
                s"service:jmx:rmi://localhost/jndi/rmi://localhost:$jmxPort/jmxrmi")
            server = JMXConnectorServerFactory
                .newJMXConnectorServer(url, null, mbs)
            server.start()
            Log.info("JMX Connector server started.")
            notifyStarted()
        } catch {
            case NonFatal(e) =>
                Log.debug(s"Failed to start Jmx Connector Server: ${e.getMessage}")
                notifyFailed(e)
        }
    }

    override def doStop(): Unit = {
        try {
            server.stop()
            Log.info("JMX Connector server stopped.")
            notifyStopped()
        } catch {
            case NonFatal(e) =>
                Log.debug(s"Failed to stop Jmx Connector Server: ${e.getMessage}")
                notifyFailed(e)
        }
    }

}
