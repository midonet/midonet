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

import java.io.FileDescriptor
import java.net.Socket
import java.net.InetSocketAddress
import java.nio.channels.spi.SelectorProvider
import java.nio.channels.SocketChannel
import java.nio.channels.ServerSocketChannel

import sun.nio.ch.SelChImpl

import org.midonet.netlink.UnixDomainChannel
import org.midonet.util.AfUnix

/*
 * NOTE(yamamoto): This is basically a wrapper of UnixDomainSocketChannel
 * to overcome ServerSocketChannel/SocketChannel assumptions in jetty-9.3.
 * Hopefully we can get rid of most of these with jetty-9.4.x.
 */

class UnixDomainSocketChannel(val underlyingChannel: UnixDomainChannel,
                              provider: SelectorProvider)
    extends SocketChannel(provider) with SelChImpl {

    override protected def implConfigureBlocking(block: Boolean): Unit =
        underlyingChannel.configureBlocking(block)

    override protected def implCloseSelectableChannel(): Unit =
        underlyingChannel.close()

    override def read(buffers: Array[java.nio.ByteBuffer], offset: Int,
                      length: Int): Long =
        underlyingChannel.read(buffers, offset, length)

    override def read(buffers: java.nio.ByteBuffer): Int =
        underlyingChannel.read(buffers)

    override def write(buffers: Array[java.nio.ByteBuffer], offset: Int,
                       length: Int): Long =
        underlyingChannel.write(buffers, offset, length)

    override def write(buffers: java.nio.ByteBuffer): Int =
        underlyingChannel.write(buffers)

    // Hack for chooseSelector
    override def getRemoteAddress(): java.net.SocketAddress =
        new AfUnix.Address("dummy");

    // Hack for ChannelEndPoint
    def socket: Socket = new Socket {
        override def isOutputShutdown: Boolean = false
        override def shutdownOutput: Unit = underlyingChannel.close()  // XXX
        override def getLocalSocketAddress: InetSocketAddress =
            new InetSocketAddress(8888)
        override def getRemoteSocketAddress: InetSocketAddress =
            new InetSocketAddress(9999)
    }

    // SelChImpl

    def getFD(): FileDescriptor =
        underlyingChannel.getFD()

    def getFDVal(): Int =
        underlyingChannel.getFDVal()

    def kill(): Unit =
        underlyingChannel.kill()

    def translateAndSetInterestOps(ops: Int,
                                   sk: sun.nio.ch.SelectionKeyImpl): Unit =
        underlyingChannel.translateAndSetInterestOps(ops, sk)

    def translateAndSetReadyOps(ops: Int,
                                sk: sun.nio.ch.SelectionKeyImpl): Boolean =
        underlyingChannel.translateAndSetReadyOps(ops, sk)

    def translateAndUpdateReadyOps(ops: Int,
                                   sk: sun.nio.ch.SelectionKeyImpl): Boolean =
        underlyingChannel.translateAndUpdateReadyOps(ops, sk)

    // unimplemented methods
    def setOption[T](x$1: java.net.SocketOption[T],x$2: T): java.nio.channels.SocketChannel = throw new UnsupportedOperationException()
    def shutdownInput(): java.nio.channels.SocketChannel = throw new UnsupportedOperationException()
    def shutdownOutput(): java.nio.channels.SocketChannel = throw new UnsupportedOperationException()
    def getOption[T](x$1: java.net.SocketOption[T]): T = throw new UnsupportedOperationException()
    def supportedOptions(): java.util.Set[java.net.SocketOption[_]] = throw new UnsupportedOperationException()
    def bind(x$1: java.net.SocketAddress): java.nio.channels.SocketChannel = throw new UnsupportedOperationException()
    def connect(x$1: java.net.SocketAddress): Boolean = throw new UnsupportedOperationException()
    def finishConnect(): Boolean = throw new UnsupportedOperationException()
    def getLocalAddress(): java.net.SocketAddress = throw new UnsupportedOperationException()
    def isConnected(): Boolean = throw new UnsupportedOperationException()
    def isConnectionPending(): Boolean = throw new UnsupportedOperationException()
}
