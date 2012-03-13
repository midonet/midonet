/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.agent;

import java.util.UUID;

import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.agent.config.HostAgentConfiguration;
import com.midokura.midolman.agent.interfaces.InterfaceDescription;
import com.midokura.midolman.agent.scanner.InterfaceScanner;
import com.midokura.midolman.agent.state.HostDirectory;
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

    private HostDirectory.Metadata hostMetadata;

    @Inject
    InterfaceScanner interfaceScanner;

    @Inject
    InterfaceDataUpdater interfaceDataUpdater;

    @Inject
    HostAgentConfiguration configuration;

    boolean isRunning;

    @Override
    public void run() {
        isRunning = true;

        if(hostId == null){
            log.error("HostID is null, NodeInterfaceWatcher will now exit!");
            return;
        }

        while (isRunning) {
            InterfaceDescription[] descriptions;

            descriptions = interfaceScanner.scanInterfaces();

            interfaceDataUpdater.updateInterfacesData(hostId, hostMetadata, descriptions);
            try {
                Thread.sleep(configuration.getWaitTimeBetweenScans());
            } catch (InterruptedException e) {
                log.debug("Got interrupted. Stopping watcher loop");
                break;
            }
        }
        log.info("Midolman node agent watcher thread stopped.");
    }

    /**
     * Method that will signal the main loop to stop.
     */
    public void stop() {
        log.info("Midolman node agent watcher thread stopping.");
        isRunning = false;
    }

    /**
     * It will set the host ID
     * @param hostId is the host id under which we are currently running
     */
    public void setHostId(UUID hostId) {
        this.hostId = hostId;
    }
}
