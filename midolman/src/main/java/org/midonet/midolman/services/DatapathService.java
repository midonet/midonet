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

import org.midonet.midolman.datapath.DatapathChannel;
import org.midonet.midolman.io.DatapathConnectionPool;


/**
 * Service implementation that will open a connection to the local datapath and
 * start the datapath channel.
 */
public class DatapathService extends AbstractService {

    private static final Logger log = LoggerFactory
        .getLogger(DatapathService.class);

    private final DatapathConnectionPool requestsConnPool;
    private final DatapathChannel datapathChannel;

    @Inject
    public DatapathService(DatapathConnectionPool requestsConnPool,
                           DatapathChannel datapathChannel) {
        this.requestsConnPool = requestsConnPool;
        this.datapathChannel = datapathChannel;
    }


    @Override
    protected void doStart() {
        log.debug("Starting the datapath service");
        try {
            requestsConnPool.start();
            datapathChannel.start();
            log.debug("Datapath service started");
            notifyStarted();
        } catch (Exception e) {
            notifyFailed(e);
        }
    }

    @Override
    protected void doStop() {
        try {
            datapathChannel.stop();
        } catch (Exception e) {
            log.warn("Stopping the datapath channel failed", e);
        }

        try {
            requestsConnPool.stop();
        } catch (Exception e) {
            log.warn("Stopping the datapath connections failed", e);
        }

        notifyStopped();
    }
}
