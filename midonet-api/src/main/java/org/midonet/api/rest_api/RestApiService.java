/*
 * Copyright 2012-2013 Midokura PTE LTD.
 */
package org.midonet.api.rest_api;

import com.google.common.util.concurrent.AbstractService;
import com.google.inject.Inject;
import org.midonet.midolman.services.StoreService;
import org.midonet.cluster.services.MidostoreSetupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages all the services for Midolman REST API.
 */
public class RestApiService  extends AbstractService {

    private static final Logger log = LoggerFactory.getLogger(
            RestApiService.class);

    private final MidostoreSetupService midoStoreSetupService;

    private final StoreService storeService;

    @Inject
    public RestApiService(MidostoreSetupService midoStoreSetupService,
                          StoreService storeService) {
        this.midoStoreSetupService = midoStoreSetupService;
        this.storeService = storeService;
    }

    @Override
    protected void doStart() {
        log.info("doStart: entered");

        try {
            midoStoreSetupService.startAndWait();
            storeService.startAndWait();
            notifyStarted();
        } catch (Exception e) {
            log.error("Exception while starting service", e);
            notifyFailed(e);
        } finally {
            log.info("doStart: exiting");
        }
    }

    @Override
    protected void doStop() {
        log.info("doStop: entered");

        try {
            storeService.stopAndWait();
            midoStoreSetupService.stopAndWait();
            notifyStopped();
        } catch (Exception e) {
            log.error("Exception while stopping service", e);
            notifyFailed(e);
        } finally {
            log.info("doStop: exiting");
       }
    }
}
