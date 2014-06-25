/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.api.license;

import com.google.common.util.concurrent.AbstractService;
import com.google.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LicenseService extends AbstractService {

    private static final Logger log = LoggerFactory.getLogger(
        LicenseService.class);

    private final LicenseManager manager;

    @Inject
    public LicenseService(LicenseManager manager) {
        this.manager = manager;
    }

    @Override
    public void doStart() {
        log.info("doStart: entered");

        try {
            // Load the licenses.
            manager.load();
            notifyStarted();
        } catch (Exception e) {
            log.error("Exception while starting the license service", e);
            notifyFailed(e);
        } finally {
            log.info("doStart: exiting");
        }
    }

    @Override
    public void doStop() {
        log.info("doStop: entered");

        try {
            // Save the licenses.
            manager.save();
            notifyStopped();
        } catch (Exception e) {
            log.error("Exception while stopping the license service", e);
            notifyFailed(e);
        } finally {
            log.info("doStop: exiting");
        }
    }
}
