/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice.reactor;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import com.google.inject.Inject;
import com.google.inject.Provider;

import com.midokura.midolman.config.MidolmanConfig;

/**
 * Provides an instance of a {@link ScheduledExecutorService} implementation.
 */
public class ScheduledExecutorServiceProvider implements
                                              Provider<ScheduledExecutorService> {
    @Override
    public ScheduledExecutorService get() {
        return Executors.newScheduledThreadPool(1);
    }
}
