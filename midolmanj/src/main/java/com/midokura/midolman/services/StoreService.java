/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.services;

import com.google.common.util.concurrent.AbstractService;
import com.google.inject.Inject;
import com.midokura.midolman.monitoring.store.Store;
import me.prettyprint.hector.api.exceptions.HectorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for Cassandra connection
 */
public class StoreService extends AbstractService {

    private static final Logger log =
            LoggerFactory.getLogger(StoreService.class);

    @Inject
    Store store;

    @Override
    protected void doStart() {

        try {
            store.initialize();
            notifyStarted();
        } catch (HectorException ex) {
            log.error("Could not connect to Cassandra", ex);
            notifyFailed(ex);
        }

    }

    @Override
    protected void doStop() {
        notifyStopped();
    }

}
