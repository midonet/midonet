/******************************************************************************
 *                                                                            *
 *      Copyright (c) 2013 Midokura SARL, All Rights Reserved.         *
 *                                                                            *
 ******************************************************************************/

package org.midonet.odp;

import java.io.IOException;
import java.nio.channels.SelectionKey;

import org.midonet.util.Bucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.netlink.Netlink;
import org.midonet.odp.protos.OvsDatapathConnection;
import org.midonet.util.eventloop.*;

public abstract class DatapathClient {

    private static final Logger log =
        LoggerFactory.getLogger(DatapathClient.class);

    public static OvsDatapathConnection createConnection() throws Exception {

        log.info("Creating the selector loop");
        final SelectLoop loop = new SimpleSelectLoop();

        log.info("Making the ovsConnection");
        final OvsDatapathConnection ovsConnection =
            OvsDatapathConnection.create(new Netlink.Address(0));

        ovsConnection.bypassSendQueue(true);

        log.info("Setting the channel to non blocking");
        ovsConnection.getChannel().configureBlocking(false);

        log.info("Registering the channel into the selector");
        loop.register(ovsConnection.getChannel(), SelectionKey.OP_READ,
                new SelectListener() {
                    @Override
                    public void handleEvent(SelectionKey key)
                            throws IOException {
                        ovsConnection.handleReadEvent(Bucket.BOTTOMLESS);
                    }
                });

        Thread loopThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    log.info("Entering loop");
                    loop.doLoop();
                } catch (IOException e) {
                    log.error("Error in io loop: {}", e);
                    System.exit(1);
                }
            }
        });

        log.info("Starting the selector loop");
        loopThread.start();

        log.info("Initializing ovs connection");
        ovsConnection.futures.initialize().get();

        return ovsConnection;
    }
}
