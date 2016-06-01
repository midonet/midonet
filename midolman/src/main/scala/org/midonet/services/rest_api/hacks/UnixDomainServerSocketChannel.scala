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

package org.midonet.services.rest_api.hacks

import java.nio.channels.spi.SelectorProvider
import java.nio.channels.SocketChannel
import java.nio.channels.ServerSocketChannel

import org.midonet.netlink.UnixDomainChannel
import org.midonet.netlink.Netlink
import org.midonet.netlink.NetlinkSelectorProvider
import org.midonet.util.AfUnix

/*
 * NOTE(yamamoto): This is basically a wrapper of UnixDomainSocketChannel
 * to overcome ServerSocketChannel/SocketChannel assumptions in jetty-9.3.
 * Hopefully we can get rid of most of these with jetty-9.4.x.
 */

object UnixDomainServerSocketChannel {
    def open(): ServerSocketChannel = {
        val provider: NetlinkSelectorProvider = Netlink.selectorProvider()
        val channel = provider.openUnixDomainSocketChannel(
            AfUnix.Type.SOCK_STREAM)
        new UnixDomainServerSocketChannel(channel, provider)
    }
}

class UnixDomainServerSocketChannel(val underlyingChannel: UnixDomainChannel,
                                    provider: SelectorProvider)
    extends ServerSocketChannel(provider) {

    override def accept(): java.nio.channels.SocketChannel = {
        val child = underlyingChannel.accept()
        return new UnixDomainSocketChannel(child, provider)
    }

    override def bind(address: java.net.SocketAddress,
                      backlog: Int): ServerSocketChannel = {
        underlyingChannel.bind(address)
        this
    }

    override def socket(): java.net.ServerSocket =
        throw new UnsupportedOperationException()

    override protected def implCloseSelectableChannel(): Unit =
        underlyingChannel.close()

    // unimplemented methods
    override protected def implConfigureBlocking(x$1: Boolean): Unit = throw new UnsupportedOperationException()
    override def getLocalAddress(): java.net.SocketAddress = throw new UnsupportedOperationException()
    override def setOption[T](x$1: java.net.SocketOption[T],x$2: T): java.nio.channels.ServerSocketChannel = throw new UnsupportedOperationException()
    override def getOption[T](x$1: java.net.SocketOption[T]): T = throw new UnsupportedOperationException()
    override def supportedOptions(): java.util.Set[java.net.SocketOption[_]] = throw new UnsupportedOperationException()
}
