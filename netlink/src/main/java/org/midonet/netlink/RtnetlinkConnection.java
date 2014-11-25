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

package org.midonet.netlink;

import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 */
public class RtnetlinkConnection extends AbstractRtnetlinkConnection {

    private static final Logger log = LoggerFactory
            .getLogger(RtnetlinkConnection.class);


    public interface NotificationHandler {
        void notify(short type, int seq, int pid, ByteBuffer buf);
    }

    NotificationHandler notificationHandler;

    public RtnetlinkConnection(NetlinkChannel channel, BufferPool sendPool,
                               NotificationHandler notificationHandler) {
        super(channel, sendPool);
        this.notificationHandler = notificationHandler;
    }

    public static RtnetlinkConnection create(Netlink.Address address, BufferPool sendPool, int groups,
                                             NotificationHandler notificationHandler) {
        NetlinkChannel channel;

        try {
            channel = Netlink.selectorProvider()
                    .openNetlinkSocketChannel(NetlinkProtocol.NETLINK_ROUTE, groups);
            if (channel == null)
                log.error("Error creating a NetlinkChannel. Presumably, java.library.path is not set");
            channel.connect(address);
        } catch (Exception e) {
            log.error("Error connecting to rtnetlink");
            throw new RuntimeException(e);
        }

        return new RtnetlinkConnection(channel, sendPool, notificationHandler);
    }

    @Override
    protected void handleNotification(short type, byte cmd, int seq, int pid, ByteBuffer buf) {
        if (notificationHandler != null)
            notificationHandler.notify(type, seq, pid, buf);
    }

}
