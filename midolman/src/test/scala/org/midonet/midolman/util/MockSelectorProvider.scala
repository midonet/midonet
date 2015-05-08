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

package org.midonet.midolman.util

import java.net.ProtocolFamily
import java.nio.channels.spi.{AbstractSelector, SelectorProvider}
import java.nio.channels.{DatagramChannel, Pipe, ServerSocketChannel, SocketChannel}

class MockSelectorProvider extends SelectorProvider {
    override def openDatagramChannel(): DatagramChannel = ???
    override def openDatagramChannel(family: ProtocolFamily): DatagramChannel = ???
    override def openServerSocketChannel(): ServerSocketChannel = ???
    override def openSocketChannel(): SocketChannel = ???
    override def openSelector(): AbstractSelector = new MockSelector(this)
    override def openPipe(): Pipe = ???
}
