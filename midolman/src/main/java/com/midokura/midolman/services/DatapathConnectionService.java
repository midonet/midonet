/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.services;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;

import com.google.common.util.concurrent.AbstractService;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.netlink.protos.OvsDatapathConnection;
import com.midokura.util.eventloop.SelectListener;
import com.midokura.util.eventloop.SelectLoop;

/**
 * Service implementation that will open a connection to the local datapath when started.
 */
public class DatapathConnectionService extends AbstractService {

    private static final Logger log = LoggerFactory
        .getLogger(DatapathConnectionService.class);

    @Inject
    SelectLoop selectLoop;

    @Inject
    OvsDatapathConnection datapathConnection;

    @Override
    protected void doStart() {
        try {
            datapathConnection.getChannel().configureBlocking(false);

            selectLoop.register(
                datapathConnection.getChannel(),
                SelectionKey.OP_READ,
                new SelectListener() {
                    @Override
                    public void handleEvent(SelectionKey key)
                        throws IOException {
                        datapathConnection.handleEvent(key);
                    }
                });

            datapathConnection.initialize().get();
            notifyStarted();
        } catch (ClosedChannelException e) {
            notifyFailed(e);
        } catch (Exception e) {
            notifyFailed(e);
        }
    }

    @Override
    protected void doStop() {
        try {
            selectLoop.unregister(datapathConnection.getChannel(),
                                  SelectionKey.OP_READ);
        } catch (ClosedChannelException e) {
            log.error("Exception while unregistering the datapath connection " +
                          "from the selector loop.", e);
        }

        notifyStopped();
    }
}
