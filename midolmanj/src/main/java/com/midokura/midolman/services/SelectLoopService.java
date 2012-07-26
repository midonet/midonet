/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.services;

import java.io.IOException;

import com.google.common.util.concurrent.AbstractService;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.util.eventloop.SelectLoop;

/**
 * // TODO: mtoader ! Please explain yourself.
 */
public class SelectLoopService extends AbstractService {

    private static final Logger log = LoggerFactory
        .getLogger(SelectLoopService.class);

    @Inject
    SelectLoop selectLoop;

    Thread selectLoopThread;

    @Override
    protected void doStart() {

        log.info("Starting the select loop service.");
        try {
            selectLoopThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        selectLoop.doLoop();
                    } catch (IOException e) {
                        notifyFailed(e);
                    }
                }
            });

            selectLoopThread.start();
            notifyStarted();
            log.info("Started correctly");
        } catch (Exception e) {
            notifyFailed(e);
        }
    }

    @Override
    protected void doStop() {
        // TODO: change the SelectLoop to support shutdown and use it here to stop the thread
        // cleanly
        selectLoopThread.stop();
        selectLoop.shutdown();
        notifyStopped();
    }
}
