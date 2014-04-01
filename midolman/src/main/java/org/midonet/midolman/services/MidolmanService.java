/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.services;

import com.google.common.base.Service;
import com.google.common.util.concurrent.AbstractService;
import com.google.inject.Inject;
import com.yammer.metrics.core.MetricsRegistry;
import com.yammer.metrics.reporting.JmxReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.host.services.HostService;

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

    @Inject
    MetricsRegistry metrics;

    @Inject(optional = true)
    HostService hostService;

    @Inject(optional = true)
    StoreService storeService;

    @Override
    protected void doStart() {
        startService(datapathConnectionService);
        startService(selectLoopService);
        startService(actorsService);
        startService(hostService);
        startService(storeService);
        JmxReporter.startDefault(metrics);
        notifyStarted();
    }

    @Override
    protected void doStop() {
        try {
            stopService(storeService);
            stopService(hostService);
            stopService(actorsService);
            stopService(selectLoopService);
            stopService(datapathConnectionService);

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
