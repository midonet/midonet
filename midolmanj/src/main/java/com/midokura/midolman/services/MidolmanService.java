/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.services;

import com.google.common.base.Service;
import com.google.common.util.concurrent.AbstractService;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.host.services.HostAgentService;

/**
 * Basic controller of the internal midolman services.
 *
 * Has the responsibility of starting/stopping them when needed and in the
 * proper order.
 */
public class MidolmanService extends AbstractService {

    private static final Logger log = LoggerFactory
        .getLogger(MidolmanService.class);

    @Inject
    MidolmanActorsService actorsService;

    @Inject
    DatapathConnectionService datapathConnectionService;

    @Inject
    SelectLoopService selectLoopService;

    @Inject(optional = true)
    HostAgentService hostAgentService;


    @Override
    protected void doStart() {
        startService(selectLoopService);
        startService(datapathConnectionService);
        startService(actorsService);
        startService(hostAgentService);
        notifyStarted();
    }

    @Override
    protected void doStop() {
        try {
            stopService(hostAgentService);
            stopService(actorsService);
            stopService(datapathConnectionService);
            stopService(selectLoopService);

            notifyStopped();
        } catch (Exception e) {
            notifyFailed(e);
        }
    }

    private void stopService(Service service) {
        if (service == null)
            return;

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
        if (service == null)
            return;

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
