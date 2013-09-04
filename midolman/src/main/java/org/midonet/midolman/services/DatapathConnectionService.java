/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.services;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;

import com.google.common.util.concurrent.AbstractService;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.guice.reactor.ReactorModule;
import org.midonet.odp.protos.OvsDatapathConnection;
import org.midonet.util.eventloop.SelectListener;
import org.midonet.util.eventloop.SelectLoop;


/**
 * Service implementation that will open a connection to the local datapath when started.
 */
public class DatapathConnectionService extends AbstractService {

    private static final Logger log = LoggerFactory
        .getLogger(DatapathConnectionService.class);

    @Inject
    @ReactorModule.WRITE_LOOP
    SelectLoop writeLoop;

    @Inject
    @ReactorModule.READ_LOOP
    SelectLoop readLoop;

    @Inject
    OvsDatapathConnection datapathConnection;

    @Inject
    MidolmanConfig config;

    @Override
    protected void doStart() {
        try {
            datapathConnection.getChannel().configureBlocking(false);
            datapathConnection.setMaxBatchIoOps(config.getMaxMessagesPerBatch());

            readLoop.register(
                datapathConnection.getChannel(),
                SelectionKey.OP_READ,
                new SelectListener() {
                    @Override
                    public void handleEvent(SelectionKey key)
                        throws IOException {
                        datapathConnection.handleReadEvent(key);
                    }
                });

            writeLoop.registerForInputQueue(
                    datapathConnection.getSendQueue(),
                    datapathConnection.getChannel(),
                    SelectionKey.OP_WRITE,
                    new SelectListener() {
                        @Override
                        public void handleEvent(SelectionKey key)
                                throws IOException {
                            datapathConnection.handleWriteEvent(key);
                        }
                    });

            datapathConnection.initialize().get();
            notifyStarted();
        } catch (Exception e) {
            log.error("failed to start DatapathConnectionService: {}", e);
            notifyFailed(e);
        }
    }

    @Override
    protected void doStop() {
        try {
            readLoop.unregister(datapathConnection.getChannel(),
                                SelectionKey.OP_READ);
            writeLoop.unregister(datapathConnection.getChannel(),
                                SelectionKey.OP_WRITE);
        } catch (ClosedChannelException e) {
            log.error("Exception while unregistering the datapath connection " +
                          "from the selector loop.", e);
        }

        notifyStopped();
    }
}
