/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice.reactor;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;

import com.google.inject.Inject;
import com.google.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.util.eventloop.SelectLoop;

/**
 * // TODO: mtoader ! Please explain yourself.
 */
public class SelectLoopProvider implements Provider<SelectLoop> {

    private static final Logger log = LoggerFactory
        .getLogger(SelectLoopProvider.class);

    @Inject
    ScheduledExecutorService executorService;

    @Override
    public SelectLoop get() {
        try {
            return new SelectLoop(executorService);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
