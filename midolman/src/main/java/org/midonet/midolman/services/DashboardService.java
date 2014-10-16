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
import com.google.inject.Injector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.midonet.midolman.config.MidolmanConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

public class DashboardService extends AbstractService {
    private static final Logger log =
            LoggerFactory.getLogger(DashboardService.class);

    @Inject
    MidolmanConfig config;

    @Inject
    Injector injector;

    private Server server = null;

    @Override
    protected void doStart() {

        if (!config.getDashboardEnabled()) {
            notifyStarted();
            return;
        }

        log.debug("Starting jetty server");

        try {
            InputStream inputStream = new FileInputStream(
                    new File(config.pathToJettyXml()));
            XmlConfiguration configuration =
                    new XmlConfiguration(inputStream);
            server = (Server) configuration.configure();
            server.start();
            notifyStarted();
        } catch (Exception e) {
            log.error("while starting jetty server", e);
            server = null;
            notifyFailed(e);
        }
    }

    @Override
    protected void doStop() {
        try {
            if (server != null) {
                log.debug("Stopping jetty server");
                server.stop();
            }
            notifyStopped();
        } catch (Exception e) {
            log.error("while stopping jetty server", e);
            notifyFailed(e);
        }
    }
}
