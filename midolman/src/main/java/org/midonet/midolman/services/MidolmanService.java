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
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.codahale.metrics.JmxReporter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.util.concurrent.AbstractService;
import com.google.common.util.concurrent.Service;
import com.google.inject.Inject;

import org.midonet.insights.Insights;
import org.midonet.midolman.host.services.QosService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.PacketWorkersService;
import org.midonet.midolman.host.services.HostService;
import org.midonet.midolman.host.services.TcRequestHandler;
import org.midonet.midolman.management.SimpleHTTPServerService;
import org.midonet.midolman.state.PeerResolver;
import org.midonet.midolman.topology.VirtualToPhysicalMapper;
import org.midonet.midolman.topology.VirtualTopology;
import org.midonet.midolman.vpp.VppController;

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
    PacketWorkersService packetWorkersService;

    @Inject
    QosService qosService;

    @Inject
    DatapathService datapathService;

    @Inject
    SelectLoopService selectLoopService;

    @Inject
    TcRequestHandler tcRequestHandler;

    @Inject
    MetricRegistry metrics;

    @Inject(optional = true)
    HostService hostService;

    @Inject
    VirtualTopology virtualTopology;

    @Inject
    VirtualToPhysicalMapper virtualToPhysicalMapper;

    @Inject
    VppController vppController;

    @Inject
    PeerResolver resolver;

    @Inject(optional = true)
    SimpleHTTPServerService statsHttpService;

    @Inject(optional = true)
    Insights insights;

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

        log.info("JMX reporter stopped.");

        List<Service> services = services();
        Collections.reverse(services);
        log.info("Stopping services");
        for (Service service : services) {
            boolean running = service.state() == State.RUNNING;
            try {
                if (running) {
                    log.info("Stopping service: {}", service);
                    service.stopAsync().awaitTerminated();
                    log.info("Service {} stopped.", service);
                }
            } catch (Exception e) {
                log.error("Exception while stopping the service {}", service, e);
                notifyFailed(e);
                // Keep stopping services.
            }
        }

        log.info("Stopping virtual topology executors");
        int leftInVt = virtualTopology.vtExecutor().shutdownNow().size();
        int leftInIo = virtualTopology.ioExecutor().shutdownNow().size();

        if (leftInVt > 0)
            log.warn(leftInVt + " tasks remained in the virtual topology"
                     + " executor queue before closing.");

        if (leftInIo > 0)
            log.warn(leftInIo + " tasks remained in the virtual topology"
                     + " IO executor queue before closing.");

        log.info("Virtual topology executors stopped successfully.");

        log.info("Stopping rule event logger");
        virtualTopology.stopRuleLogEventChannel();
        log.info("Rule event logger stopped");

        if (state() != State.FAILED)
            notifyStopped();
    }

    private List<Service> services() {
        ArrayList<Service> services = new ArrayList<>(12);
        // Virtual Topology Service should go first as other services rely on it
        services.add(virtualTopology);
        services.add(datapathService);
        services.add(selectLoopService);
        if (hostService != null)
            services.add(hostService);
        services.add(virtualToPhysicalMapper);
        services.add(actorsService);
        services.add(packetWorkersService);
        services.add(tcRequestHandler);
        services.add(vppController);
        services.add(qosService);
        if (statsHttpService != null)
            services.add(statsHttpService);
        if (insights != null)
            services.add(insights);
        return services;
    }
}
