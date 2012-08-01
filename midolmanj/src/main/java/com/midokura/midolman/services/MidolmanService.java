/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.services;

import com.google.common.base.Service;
import com.google.common.util.concurrent.AbstractService;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.state.ZkConnection;

/**
 * Basic controller of the internal midolman services.
 *
 * Has the responsability of starting/stopping them when needed and in the
 * proper order.
 */
public class MidolmanService extends AbstractService {

    private static final Logger log = LoggerFactory
        .getLogger(MidolmanService.class);

    @Inject
    MidolmanActorsService actorsService;

    @Inject
    NetlinkConnectionService netlinkConnectionService;

    @Inject
    SelectLoopService selectLoopService;

    @Inject
    ZkConnection zkConnection;

    @Override
    protected void doStart() {
        startService(selectLoopService);
        startService(netlinkConnectionService);
        startService(actorsService);
        notifyStarted();
    }

    @Override
    protected void doStop() {
        try {
            stopService(actorsService);
            stopService(netlinkConnectionService);
            stopService(selectLoopService);

            zkConnection.close();
            notifyStopped();
        } catch (Exception e) {
            notifyFailed(e);
        }
    }

    private void stopService(Service service) {
        try {
            log.info("Service: {}.", service);
            service.stopAndWait();
        } catch (Exception e) {
            log.error("Exception while stopping the service \"{}\"", service, e);
        } finally {
            log.info("Service {}", service);
        }
    }


    protected void startService(Service service) {
        log.info("Service {}", service);
        try {
            service.startAndWait();
        } catch (Exception e) {
            log.error("Exception while starting service {}", service, e);
        } finally {
            log.info("Service {}", service);
        }
    }
}
