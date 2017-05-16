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

package org.midonet.quagga

import akka.actor.{Actor, ActorRef}
import akka.event.LoggingReceive
import java.io._
import java.nio.channels.{ByteChannel, Channels}

import org.midonet.midolman.state.NoStatePathException
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.packets.IPv4Addr

case object ProcessMessage

case class ZebraContext(ifAddr: IPv4Addr, ifName: String, clientId: Int,
                        handler: ZebraProtocolHandler,
                        in: DataInputStream, out: DataOutputStream)

object ZebraConnection {
    // The mtu size 1300 to avoid ovs dropping packets.
    final val MidolmanMTU = 1300
}

class ZebraConnection(val dispatcher: ActorRef,
                      val handler: ZebraProtocolHandler,
                      val ifAddr: IPv4Addr,
                      val ifName: String,
                      val clientId: Int,
                      val channel: ByteChannel)
    extends Actor with ActorLogWithoutPath {

    override def logSource = s"org.midonet.routing.bgp.zebra-server-$ifName"

    implicit def inputStreamWrapper(in: InputStream) = new DataInputStream(in)
    implicit def outputStreamWrapper(out: OutputStream) = new DataOutputStream(out)

    val in: DataInputStream = Channels.newInputStream(channel)
    val out: DataOutputStream = Channels.newOutputStream(channel)

    val zebraContext = ZebraContext(ifAddr, ifName, clientId, handler, in, out)

    override def postStop() = {
        if (channel.isOpen)
            channel.close()
    }

    override def receive = LoggingReceive {
        case ProcessMessage =>
            try {
                ZebraProtocol.handleMessage(zebraContext)
                self ! ProcessMessage
            } catch {
                case e: NoStatePathException =>
                    log.warn("config isn't in the directory")
                    dispatcher ! ConnectionClosed(clientId)
                case e: EOFException =>
                    log.warn("connection closed by the peer")
                    dispatcher ! ConnectionClosed(clientId)
                case e: IOException =>
                    log.warn("IO error", e)
                    dispatcher ! ConnectionClosed(clientId)
                case e: Throwable =>
                    log.warn("unexpected error", e)
                    dispatcher ! ConnectionClosed(clientId)
            }

        case _ => {
            log.error("received unknown request")
        }
    }
}
