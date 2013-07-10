/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.services;

import java.io.IOException;

import com.google.common.util.concurrent.AbstractService;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.util.eventloop.Reactor;
import org.midonet.util.eventloop.SelectLoop;
import org.midonet.midolman.guice.reactor.ReactorModule;

/**
 * Service implementation that will initialize the SelectLoop select thread.
 */
public class SelectLoopService extends AbstractService {

    private static final Logger log = LoggerFactory
        .getLogger(SelectLoopService.class);

    @Inject
    @ReactorModule.WRITE_LOOP
    SelectLoop writeLoop;

    @Inject
    @ReactorModule.READ_LOOP
    SelectLoop readLoop;

    @Inject
    @ReactorModule.ZEBRA_SERVER_LOOP
    SelectLoop zebraLoop;

    @Inject
    Reactor reactor;

    Thread readLoopThread;
    Thread writeLoopThread;
    Thread zebraLoopThread;

    private Thread startLoop(final SelectLoop loop) {
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    loop.doLoop();
                } catch (IOException e) {
                    notifyFailed(e);
                }
            }
        });

        th.start();
        return th;
    }

    @Override
    protected void doStart() {

        try {
            log.info("Starting the write select loop thread.");
            writeLoopThread = startLoop(writeLoop);
            writeLoopThread.setName("write-select-loop");
            log.info("Starting the read select loop thread.");
            readLoopThread = startLoop(readLoop);
            readLoopThread.setName("read-select-loop");
            log.info("Starting the zebra server select loop thread.");
            zebraLoopThread = startLoop(zebraLoop);
            zebraLoopThread.setName("zebra-server-loop");

            notifyStarted();
            log.info("Select loop threads started correctly");
        } catch (Exception e) {
            notifyFailed(e);
        }
    }

    @Override
    protected void doStop() {
        // TODO: change the SelectLoop to support shutdown and use it here to stop the thread
        // cleanly
        reactor.shutDownNow();

        readLoop.shutdown();
        writeLoop.shutdown();
        zebraLoop.shutdown();

        readLoopThread.stop();
        writeLoopThread.stop();
        zebraLoopThread.stop();
        notifyStopped();
    }
}
