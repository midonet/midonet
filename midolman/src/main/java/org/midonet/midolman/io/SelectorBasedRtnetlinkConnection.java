package org.midonet.midolman.io;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import org.midonet.config.ConfigProvider;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.netlink.BufferPool;
import org.midonet.netlink.Callback;
import org.midonet.netlink.Netlink;
import org.midonet.netlink.Rtnetlink;
import org.midonet.netlink.RtnetlinkConnection;
import org.midonet.netlink.exceptions.NetlinkException;
import org.midonet.netlink.rtnetlink.Addr;
import org.midonet.netlink.rtnetlink.Link;
import org.midonet.netlink.rtnetlink.Neigh;
import org.midonet.netlink.rtnetlink.Route;
import org.midonet.packets.IPv4Addr;
import org.midonet.util.Bucket;
import org.midonet.util.eventloop.SelectListener;
import org.midonet.util.eventloop.SelectLoop;
import org.midonet.util.eventloop.SimpleSelectLoop;
import org.slf4j.LoggerFactory;

/**
 *
 */
public class SelectorBasedRtnetlinkConnection {

    private final static org.slf4j.Logger log =
            LoggerFactory.getLogger(SelectorBasedRtnetlinkConnection.class);

    public final String name;
    private final MidolmanConfig config;
    private final int groups;
    private final RtnetlinkConnection.NotificationHandler notificationHandler;
    private Thread thread;
    private SelectLoop loop;
    private BufferPool sendPool;
    private RtnetlinkConnection conn = null;
    private final Bucket bucket = Bucket.BOTTOMLESS;

    public SelectorBasedRtnetlinkConnection(String name, MidolmanConfig config, BufferPool sendPool, int groups,
                                            RtnetlinkConnection.NotificationHandler notificationHandler) {
        this.name = name;
        this.config = config;
        this.sendPool = sendPool;
        this.groups = groups;
        this.notificationHandler = notificationHandler;
    }

    public SelectorBasedRtnetlinkConnection(String name, MidolmanConfig config, int groups,
                                            RtnetlinkConnection.NotificationHandler notificationHandler) {
        this(name, config, new BufferPool(config.getSendBufferPoolInitialSize(),
                config.getSendBufferPoolMaxSize(),
                config.getSendBufferPoolBufSizeKb() * 1024), groups, notificationHandler);
    }

    public RtnetlinkConnection getConnection() {
        return conn;
    }

    public void start() throws IOException, InterruptedException, ExecutionException {
        if (conn == null) {
            try {
                setUp();
            } catch (IOException e) {
                try {
                    stop();
                } catch (Exception ignored) {}
                throw e;
            }
        }
    }

    private void setUp() throws IOException {
        if (conn != null)
            return;

        log.info("Starting datapath connection: {}", name);
        loop = new SimpleSelectLoop();

        thread = startLoop(loop, name);

        conn = RtnetlinkConnection.create(new Netlink.Address(0), sendPool, groups, notificationHandler);

        conn.getChannel().configureBlocking(false);
        conn.setMaxBatchIoOps(config.getMaxMessagesPerBatch());

        loop.register(
                conn.getChannel(),
                SelectionKey.OP_READ,
                new SelectListener() {
                    @Override
                    public void handleEvent(SelectionKey key)
                            throws IOException {
                        conn.handleReadEvent(bucket);
                    }
                });

        loop.registerForInputQueue(
                conn.getSendQueue(),
                conn.getChannel(),
                SelectionKey.OP_WRITE,
                new SelectListener() {
                    @Override
                    public void handleEvent(SelectionKey key)
                            throws IOException {
                        conn.handleWriteEvent();
                    }
                });
    }

    public void stop() throws Exception {
        try {
            log.info("Stopping datapath connection: {}", name);
            if (loop != null) {
                loop.unregister(conn.getChannel(), SelectionKey.OP_WRITE);
                loop.unregister(conn.getChannel(), SelectionKey.OP_READ);
                loop.shutdown();
            }
            conn.getChannel().close();
        } finally {
            conn = null;
            loop = null;
            thread = null;
        }
    }

    private Thread startLoop(final SelectLoop loop, final String threadName) {
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    loop.doLoop();
                } catch (IOException e) {
                    log.error("IOException on netlink channel, ABORTING {}", name, e);
                    System.exit(2);
                }
            }
        });

        log.info("Starting rtnetlink select loop thread: {}", threadName);
        th.start();
        th.setName(threadName);
        return th;
    }
}
