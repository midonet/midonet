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

    @Override
    protected void doStart() {
        try {
            requestsConnPool.start();
            notifyStarted();
        } catch (Exception e) {
            log.error("failed to start DatapathConnectionService", e);
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
