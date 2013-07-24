package org.midonet.odp;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.netlink.BufferPool;
import org.midonet.netlink.Netlink;
import org.midonet.netlink.NetlinkChannel;
import org.midonet.odp.protos.OvsDatapathConnection;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.eventloop.SelectListener;
import org.midonet.util.eventloop.SelectLoop;
import org.midonet.util.eventloop.TryCatchReactor;

public abstract class DatapathClient {

    private static final Logger log =
        LoggerFactory.getLogger(DatapathClient.class);

    public static OvsDatapathConnection createConnection() throws Exception {

        log.info("Creating the selector loop");
        final SelectLoop loop = new SelectLoop();
        final Reactor reactor = new TryCatchReactor("ovs-connection", 1);

        log.info("Making the ovsConnection");
        final OvsDatapathConnection ovsConnection =
            OvsDatapathConnection.create(new Netlink.Address(0), reactor);

        log.info("Setting the channel to non blocking");
        ovsConnection.getChannel().configureBlocking(false);

        log.info("Registering the channel into the selector");
        loop.register(ovsConnection.getChannel(), SelectionKey.OP_READ,
                new SelectListener() {
                    @Override
                    public void handleEvent(SelectionKey key)
                            throws IOException {
                        ovsConnection.handleReadEvent(key);
                    }
                });


        loop.registerForInputQueue(
            ovsConnection.getSendQueue(),
            ovsConnection.getChannel(),
            SelectionKey.OP_WRITE,
            new SelectListener() {
                @Override
                public void handleEvent(SelectionKey key)
                    throws IOException {
                        ovsConnection.handleWriteEvent(key);
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
