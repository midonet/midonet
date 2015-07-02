/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.api.rest_api;

import com.google.common.util.concurrent.AbstractService;
import com.google.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.services.LegacyStorageService;
import org.midonet.cluster.services.MidonetBackend;

/**
 * Manages all the services for Midolman REST API.
 */
public class RestApiService extends AbstractService {

    private static final Logger log = LoggerFactory.getLogger(
            RestApiService.class);

    private final MidonetBackend midonetBackendService;
    private final LegacyStorageService legacyStorageService;

    @Inject
    public RestApiService(MidonetBackend midonetBackendService,
                          LegacyStorageService legacyStorageService) {
        this.midonetBackendService = midonetBackendService;
        this.legacyStorageService = legacyStorageService;
    }

    @Override
    protected void doStart() {
        log.info("doStart: entered");

        try {
            legacyStorageService.startAsync().awaitRunning();
            midonetBackendService.startAsync().awaitRunning();
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
            midonetBackendService.stopAsync().awaitTerminated();
        } catch (Exception e) {
            log.error("Exception while stopping service", e);
            notifyFailed(e);
        }

        try {
            legacyStorageService.stopAsync().awaitTerminated();
            notifyStopped();
        } catch (Exception e) {
            log.error("Exception while stopping service", e);
            notifyFailed(e);
        } finally {
            log.info("doStop: exiting");
        }
    }
}
