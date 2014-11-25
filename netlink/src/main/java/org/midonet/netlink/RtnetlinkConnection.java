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

    public static RtnetlinkConnection create( Netlink.Address address, BufferPool sendPool, int groups,
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
