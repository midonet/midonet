/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.services;

import java.io.IOException;

import com.google.common.util.concurrent.AbstractService;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.guice.MidolmanActorsModule;
import org.midonet.util.eventloop.SelectLoop;

/**
 * Service implementation that will initialize the SelectLoop select thread.
 */
public class SelectLoopService extends AbstractService {

    private static final Logger log = LoggerFactory
        .getLogger(SelectLoopService.class);

    @Inject
    @MidolmanActorsModule.ZEBRA_SERVER_LOOP
    SelectLoop zebraLoop;

    private Thread startLoop(final SelectLoop loop, final String name) {
        log.info("Starting select loop thread: {}.", name);
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

        th.setName(name);
        th.start();
        return th;
    }

    @Override
    protected void doStart() {
        try {
            startLoop(zebraLoop, "zebra-server-loop");
            notifyStarted();
            log.info("Select loop threads started correctly");
        } catch (Exception e) {
            notifyFailed(e);
        }
    }

    @Override
    protected void doStop() {
        zebraLoop.shutdown();
        notifyStopped();
    }
}
