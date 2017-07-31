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

package org.midonet.cluster.services.endpoint.users

import scala.reflect.ClassTag

import com.google.protobuf.Message

import org.midonet.cluster.services.endpoint.comm.{ConnectionHandler, IncomingHandler, ProtoBufWSAdapter}

import io.netty.channel.Channel
import io.netty.channel.socket.SocketChannel

/**
  * Trait for those who want want an endpoint channel to do api communication
  * via protobuffed websockets using a ConnectionManager.
  */
trait ProtoBufWSApiEndpointUser[Req <: Message, Res <: Message]
    extends EndpointUser {

    protected val reqPrototype: Req
    protected def apiConnectionHandler: ConnectionHandler[Req, Res]

    // Needed to instantiate IncomingHandler as it requires an implicit
    // ClassTag[Req] on the constructor. Usually, Scala generates these
    // ClassTags automatically when it knows the type but with generics
    // this doesn't seem to be the case so we have to help it out here.
    implicit private lazy val reqClassTag: ClassTag[Req] =
        ClassTag(reqPrototype.getClass)

    override def protocol(sslEnabled: Boolean): String =
        if (sslEnabled) "wss" else "ws"

    /**
      * Initialize handlers required for integration with midonet api Connection
      * classes.
      *
      * @param path    Path part of the URL of the first HTTP request handled.
      * @param channel Channel to be initialized.
      */
    override def initEndpointChannel(path: String, channel: Channel) = {
        channel match {
            case socketChannel: SocketChannel =>
                val protoBufWSAdapter = new ProtoBufWSAdapter(path,
                                                              reqPrototype)
                protoBufWSAdapter.initChannel(socketChannel)
            case _ =>
                throw new IllegalArgumentException("Expecting a socket channel")
        }

        channel.pipeline.addLast(
            new IncomingHandler[Req](apiConnectionHandler))
    }
}

