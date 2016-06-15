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

package org.midonet.cluster.services.state.server

import scala.concurrent.Future

import org.midonet.cluster.rpc.State.ProxyResponse

/**
  * A trait adapting high-level calls that close a client connection and
  * sends messages to a remote client to a Netty channel adapter.
  */
trait ClientHandler {

    /**
      * Closes the client handler and releases the underlying I/O resources.
      */
    def close(): Future[AnyRef]

    /**
      * Sends a response message. The method returns a message that completes
      * when the message has been sent.
      */
    def send(message: ProxyResponse): Future[AnyRef]

}
