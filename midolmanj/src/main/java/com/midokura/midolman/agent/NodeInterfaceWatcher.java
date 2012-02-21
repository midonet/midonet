/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.agent;

import java.util.List;
import java.util.UUID;

import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.agent.config.HostAgentConfiguration;
import com.midokura.midolman.agent.interfaces.InterfaceDescription;
import com.midokura.midolman.agent.scanner.InterfaceScanner;
import com.midokura.midolman.agent.state.HostDirectory;
import com.midokura.midolman.agent.state.HostZkManager;
import com.midokura.midolman.agent.updater.InterfaceDataUpdater;

/**
 * Main interface scanning loop. Internally it uses an {@link InterfaceScanner}
 * implementation and an {@link InterfaceDataUpdater} implementation to scan
 * periodically and respectively upload the scanned data to ZooKeeper.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 2/8/12
 */
public class NodeInterfaceWatcher implements Runnable {

    private final static Logger log =
            LoggerFactory.getLogger(NodeInterfaceWatcher.class);

    private UUID hostId;

    @Inject
    InterfaceScanner interfaceScanner;

    @Inject
    InterfaceDataUpdater interfaceDataUpdater;

    @Inject
    HostAgentConfiguration configuration;

    @Inject
    HostZkManager zkManager;

    boolean isRunning;

    @Override
    public void run() {
        isRunning = true;

        try {
            identifyHost();
        } catch (InterruptedException e) {
            log.debug(
                    "Got interrupted trying to generate the host ID." +
                            "Stopping watcher loop");
            // No need to call interrupt(), there's no higher level thread
            // we want to inform
            return;
        }
        while (isRunning) {
            InterfaceDescription[] descriptions;

            interfaceList = interfaceScanner.scanInterfaces();

            interfaceDataUpdater.updateInterfacesData(interfaceList);
            try {
                Thread.sleep(configuration.getWaitTimeBetweenScans());
            } catch (InterruptedException e) {
                log.debug("Got interrupted. Stopping watcher loop");
                break;
            }
        }

        log.info("Midolman node agent watcher thread stopped.");
    }

    private void identifyHost() throws InterruptedException {
        // Try to get the host Id
        HostDirectory.Metadata metadata = new HostDirectory.Metadata();
        // TODO set some meaningful data in the metadata
        metadata.setName("Garbage");
        // If an exception is thrown it will loop forever
        while (hostId == null) {
            try {
                hostId = HostIdGenerator.getHostId(configuration, zkManager);
                if (hostId != null) {
                    zkManager.makeAlive(hostId, metadata);
                    break;
                }
            } catch (Exception e) {
                log.warn("Cannot create a unique Id.", e);
                // Reset the hostId to null to continue looping
                hostId = null;
            }
            Thread.sleep(configuration.getWaitTimeForUniqueHostId());
        }
    }

    /**
     * Method that will signal the main loop to stop.
     */
    public void stop() {
        log.info("Midolman node agent watcher thread stopping.");
        isRunning = false;
    }
}
