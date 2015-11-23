/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman.services;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.codahale.metrics.JmxReporter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.util.concurrent.AbstractService;
import com.google.common.util.concurrent.Service;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.host.services.HostService;
import org.midonet.midolman.state.PeerResolver;
import org.midonet.midolman.topology.VirtualToPhysicalMapper;

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
    MetricRegistry metrics;

    @Inject(optional = true)
    HostService hostService;

    @Inject
    VirtualToPhysicalMapper virtualToPhysicalMapper;

    @Inject
    PeerResolver resolver;

    private JmxReporter jmxReporter = null;

    @Override
    protected void doStart() {
        for (Service service : services()) {
            log.info("Starting service: {}", service);
            try {
                service.startAsync().awaitRunning();
                log.info("Service started: {}", service);
            } catch (Exception e) {
                log.error("Exception while starting service " + service, e);
                notifyFailed(e);
                doStop();
                return;
            }
        }

        try {
            jmxReporter = JmxReporter.forRegistry(metrics).build();
            jmxReporter.start();
        } catch (Exception e) {
            log.error("Cannot start metrics reporter");
            notifyFailed(e);
            doStop();
        }

        try {
            resolver.start();
        } catch (Exception e) {
            log.error("Cannot start the peer resolver");
            notifyFailed(e);
            doStop();
        }

        notifyStarted();
    }

    @Override
    protected void doStop() {

        try {
            if (jmxReporter != null) {
                jmxReporter.stop();
            }
        } catch (Exception e) {
            log.error("Could not stop jmx reporter", e);
            notifyFailed(e);
        }

        List<Service> services = services();
        Collections.reverse(services);
        log.info("Stopping services");
        for (Service service : services) {
            boolean running = service.state() == State.RUNNING;
            try {
                if (running) {
                    log.info("Stopping service: {}", service);
                    service.stopAsync().awaitTerminated();
                }
            } catch (Exception e) {
                log.error("Exception while stopping the service {}", service, e);
                notifyFailed(e);
                // Keep stopping services.
            }
        }

        if (state() != State.FAILED)
            notifyStopped();
    }

    private List<Service> services() {
        ArrayList<Service> services = new ArrayList<>(5);
        services.add(datapathConnectionService);
        services.add(selectLoopService);
        if (hostService != null)
            services.add(hostService);
        services.add(virtualToPhysicalMapper);
        services.add(actorsService);
        return services;
    }
}
