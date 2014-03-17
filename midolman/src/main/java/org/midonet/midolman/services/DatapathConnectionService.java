/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.services;

import com.google.common.util.concurrent.AbstractService;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.io.DatapathConnectionPool;


/**
 * Service implementation that will open a connection to the local datapath when started.
 */
public class DatapathConnectionService extends AbstractService {

    private static final Logger log = LoggerFactory
        .getLogger(DatapathConnectionService.class);

    @Inject
    DatapathConnectionPool requestsConnPool;

    @Inject
    MidolmanConfig config;

    @Override
    protected void doStart() {
        try {
            requestsConnPool.start();
            notifyStarted();
        } catch (Exception e) {
            log.error("failed to start DatapathConnectionService: {}", e);
            notifyFailed(e);
        }
    }

    @Override
    protected void doStop() {
        try {
            requestsConnPool.stop();
        } catch (Exception e) {
            log.error("Exception while shutting down datapath connections", e);
        }

        notifyStopped();
    }
}
