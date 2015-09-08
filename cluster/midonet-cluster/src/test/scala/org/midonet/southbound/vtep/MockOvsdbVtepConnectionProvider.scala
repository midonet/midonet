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

package org.midonet.southbound.vtep

import scala.concurrent.Future
import scala.concurrent.duration.Duration

import io.netty.channel.{Channel, ChannelFuture}
import org.opendaylight.ovsdb.lib.OvsdbClient

import scala.concurrent.duration._
import org.midonet.packets.IPv4Addr

class MockOvsdbVtepConnectionProvider extends OvsdbVtepConnectionProvider{

    override def get(mgmtIp: IPv4Addr, mgmtPort: Int,
                     retryInterval: Duration = 0 second,
                     maxRetries: Int = 0) : OvsdbVtepConnection = {
        new MockOvsdbVtepConnection(mgmtIp, mgmtPort, retryInterval, maxRetries)
    }

}

class MockOvsdbVtepConnection(mgmtIp: IPv4Addr, mgmtPort: Int,
                              retryInterval: Duration = 0 second,
                              maxRetries: Int = 0,
                              channel: Channel = null,
                              client: OvsdbClient = null)
    extends OvsdbVtepConnection(mgmtIp, mgmtPort, retryInterval, maxRetries) {
    def break(future: ChannelFuture): Unit = {
        newCloseListener(channel, client).operationComplete(future)
    }
    protected override def openChannel(): Future[Channel] = {
        Future.successful(channel)
    }
    protected override def initializeClient(channel: Channel)
    : (Channel, OvsdbClient) = {
        (channel, client)
    }
}

