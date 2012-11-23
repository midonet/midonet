package com.midokura.mmdpctl.netlink;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.spi.SelectorProvider;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.netlink.Netlink;
import com.midokura.netlink.NetlinkChannel;
import com.midokura.netlink.NetlinkSelectorProvider;
import com.midokura.netlink.protos.OvsDatapathConnection;
import com.midokura.util.eventloop.Reactor;
import com.midokura.util.eventloop.SelectListener;
import com.midokura.util.eventloop.SelectLoop;
import com.midokura.util.eventloop.TryCatchReactor;

public class NetlinkClient {

    private static final Logger log = LoggerFactory
            .getLogger(NetlinkClient.class);

    public static OvsDatapathConnection createDatapathConnection()
            throws Exception {
        SelectorProvider provider = SelectorProvider.provider();

        if (!(provider instanceof NetlinkSelectorProvider)) {
            log.error("Invalid selector type: {}", provider.getClass());
            throw new RuntimeException();
        }

        NetlinkSelectorProvider netlinkSelector = (NetlinkSelectorProvider) provider;

        final NetlinkChannel netlinkChannel =
                netlinkSelector.openNetlinkSocketChannel(Netlink.Protocol.NETLINK_GENERIC);

        if (netlinkChannel == null) {
            throw new Exception("Cannot connect to the Netlink module.");
        }

        log.info("Connecting");
        netlinkChannel.connect(new Netlink.Address(0));

        log.info("Creating the selector loop");
        final SelectLoop loop = new SelectLoop();
        final Reactor reactor = new TryCatchReactor("ovs-connection", 1);

        log.info("Making the ovsConnection");
        final OvsDatapathConnection ovsConnection =
                OvsDatapathConnection.create(netlinkChannel, reactor);

        log.info("Setting the channel to non blocking");
        netlinkChannel.configureBlocking(false);

        log.info("Registering the channel into the selector");
        loop.register(netlinkChannel, SelectionKey.OP_READ,
                new SelectListener() {
                    @Override
                    public void handleEvent(SelectionKey key)
                            throws IOException {
                        ovsConnection.handleEvent(key);
                    }
                });

        Thread loopThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    log.info("Entering loop");
                    loop.doLoop();
                } catch (IOException e) {
                    e.printStackTrace();
                    System.exit(-1);
                }
            }
        });

        log.info("Starting the selector loop");
        loopThread.start();

        log.info("Initializing ovs connection");
        ovsConnection.initialize();

        while (!ovsConnection.isInitialized()) {
            Thread.sleep(TimeUnit.MILLISECONDS.toMillis(50));
        }

        return ovsConnection;
    }


}
