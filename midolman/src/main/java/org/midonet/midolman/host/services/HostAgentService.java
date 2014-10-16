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
package org.midonet.midolman.host.services;

import com.google.common.util.concurrent.AbstractService;
import com.google.common.util.concurrent.Service;
import com.google.inject.Inject;
import org.midonet.midolman.services.DatapathConnectionService;
import org.midonet.midolman.services.SelectLoopService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Host agent internal service.
 * <p/>
 * It starts and stops the host agent service.
 */
public class HostAgentService extends AbstractService {

    private static final Logger log = LoggerFactory
            .getLogger(HostAgentService.class);

    @Inject
    DatapathConnectionService datapathConnectionService;

    @Inject
    SelectLoopService selectLoopService;

    @Inject
    HostService hostService;

    @Override
    protected void doStart() {
        startService(selectLoopService);
        startService(datapathConnectionService);
        startService(hostService);
        notifyStarted();
    }

    @Override
    protected void doStop() {
        try {
            stopService(hostService);
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
            service.stopAsync().awaitTerminated();
        } catch (Exception e) {
            log.error("Exception while stopping the service \"{}\"",
                    service, e);
        } finally {
            log.info("Service {}", service);
        }
    }

    protected void startService(Service service) {
        if (service == null)
            return;

        log.info("Service {}", service);
        try {
            service.startAsync().awaitRunning();
        } catch (Exception e) {
            log.error("Exception while starting service {}", service, e);
        } finally {
            log.info("Service {}", service);
        }
    }

}
