/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.midolman.host;

import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

import com.google.inject.Inject;
import org.midonet.midolman.host.config.HostConfig;
import org.midonet.midolman.host.scanner.InterfaceScanner;
import org.midonet.midolman.host.state.HostDirectory;
import org.midonet.midolman.host.updater.InterfaceDataUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main interface scanning loop. Internally it uses an {@link InterfaceScanner}
 * TODO this class should react to netlink events instead of polling
 */
public class HostInterfaceWatcher implements Runnable {

    private final static Logger log =
            LoggerFactory.getLogger(HostInterfaceWatcher.class);

    private UUID hostId;

    private HostDirectory.Metadata hostMetadata;

    @Inject
    InterfaceScanner interfaceScanner;

    @Inject
    InterfaceDataUpdater interfaceDataUpdater;

    @Inject
    HostConfig configuration;

    volatile boolean isRunning;

    @Override
    public void run() {
        isRunning = true;

        if (hostId == null) {
            log.error("HostID is null, HostInterfaceWatcher will now exit!");
            return;
        }

        while (isRunning) {
            interfaceDataUpdater.updateInterfacesData(
                hostId, hostMetadata, interfaceScanner.scanInterfaces());

            long deadline = System.currentTimeMillis() +
                            configuration.getWaitTimeBetweenHostScans();
            while (isRunning && System.currentTimeMillis() < deadline) {
                LockSupport.parkUntil(deadline);
            }
        }
        log.info("Midolman host watcher thread stopped.");
    }

    /**
     * Method that will signal the main loop to stop.
     */
    public void stop() {
        log.info("Midolman host watcher thread stopping.");
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
