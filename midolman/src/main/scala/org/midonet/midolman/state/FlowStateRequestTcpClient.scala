/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.midolman.state

import java.io.{DataInputStream, IOException}
import java.net.Socket
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import java.util.concurrent.TimeUnit._

import org.slf4j.LoggerFactory.getLogger

/**
  * This class handles flow state file transfers between the agent calling and a
  * different agent in the cluster. It opens up a TCP connection with the host,
  * sends the port id for the requested flows state, and receives a response
  * with the length of the flow state file and the file itself.
  */
object FlowStateRequestTcpClient {

    private val log = getLogger(getClass)

    private val socketTimeout = SECONDS.toMillis(2).toInt
    private var socket: Socket = _

    def requestFlowStateFrom(host: String, tcpPort: Int,
                             port: UUID): Array[Byte] = {
        try {
            socket = new Socket(host, tcpPort)
            socket.setSoTimeout(socketTimeout)
            val dis = new DataInputStream(socket.getInputStream)

            socket.getOutputStream().write(port.toString().getBytes(UTF_8))
            val dataLength = dis.readInt()
            val buffer = new Array[Byte](dataLength)
            dis.readFully(buffer)
            buffer
        } catch {
            case e: IOException =>
                log warn (s"Unable to get requested flow from $port", e)
                throw e
        } finally {
            socket.close()
        }
    }
}
