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

    private Server server;

    @Override
    protected void doStart() {

        if (!config.getDashboardEnabled())
            return;

        log.debug("Starting jetty server");

        try {
            InputStream inputStream = new FileInputStream(
                    new File(config.pathToJettyXml()));
            XmlConfiguration configuration =
                    new XmlConfiguration(inputStream);
            server = (Server)configuration.configure();
            server.start();
            notifyStarted();
        } catch (Exception e) {
            log.error("while starting jetty server", e);
            notifyFailed(e);
        }
    }

    @Override
    protected void doStop() {

        if (!config.getDashboardEnabled())
            return;

        log.debug("Stopping jetty server");

        try {
            server.stop();
            notifyStopped();
        } catch (Exception e) {
            log.error("while stopping jetty server", e);
            notifyFailed(e);
        }

    }
}
