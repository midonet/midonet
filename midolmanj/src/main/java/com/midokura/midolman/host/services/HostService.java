/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.host.services;

import com.google.common.util.concurrent.AbstractService;
import com.google.inject.Inject;
import com.midokura.midolman.host.HostIdGenerator;
import com.midokura.midolman.host.HostIdGenerator.HostIdAlreadyInUseException;
import com.midokura.midolman.host.HostIdGenerator.PropertiesFileNotWritableException;
import com.midokura.midolman.host.HostInterfaceWatcher;
import com.midokura.midolman.host.commands.executors.HostCommandWatcher;
import com.midokura.midolman.host.interfaces.InterfaceDescription;
import com.midokura.midolman.host.scanner.InterfaceScanner;
import com.midokura.midolman.host.state.HostDirectory;
import com.midokura.midolman.host.state.HostZkManager;
import com.midokura.midolman.host.config.HostConfig;
import com.midokura.midolman.state.StateAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Host internal service.
 * <p/>
 * It starts and stops the host service.
 */
public class HostService extends AbstractService {

    private static final Logger log = LoggerFactory
            .getLogger(HostService.class);

    private UUID hostId;

    private Thread watcherThread;

    @Inject
    HostConfig configuration;

    @Inject
    private HostCommandWatcher cmdWatcher;

    @Inject
    private HostInterfaceWatcher interfaceWatcher;

    @Inject
    private InterfaceScanner scanner;

    @Inject
    private HostZkManager zkManager;

    @Override
    protected void doStart() {

        log.info("Starting Midolman host agent.");
        try {
            hostId = identifyHostId();
            cmdWatcher.checkCommands(hostId);
            watcherThread = new Thread(interfaceWatcher);
            interfaceWatcher.setHostId(hostId);
            watcherThread.start();
            notifyStarted();
        } catch (Exception e) {
            notifyFailed(e);
            throw new RuntimeException("Couldn't generate unique host ID", e);
        }
        log.info("Midolman host agent started.");

    }

    @Override
    protected void doStop() {

        log.info("Stopping Midolman host agent.");
        try {
            // tell the watcher thread to stop
            interfaceWatcher.stop();

            // wait for the thread to finish running
            if (watcherThread != null )
                watcherThread.join();
            notifyStopped();
        } catch (InterruptedException e) {
            notifyFailed(e);
        }
        log.info("Midolman host agent stopped.");

    }

    /**
     * Scans the host and identifies the host ID.
     *
     * @return ID identified
     * @throws StateAccessException
     *              If there was a problem reading data from ZK.
     * @throws PropertiesFileNotWritableException
     *                                     If the properties file cannot
     *                                     be written
     * @throws InterruptedException
     */
    private UUID identifyHostId()
            throws StateAccessException, PropertiesFileNotWritableException,
            InterruptedException {

        log.debug("Identifying host");
        // Try to get the host Id
        HostDirectory.Metadata metadata = new HostDirectory.Metadata();

        // Retrieve the interfaces and store the addresses in the metadata
        InterfaceDescription[] interfaces = scanner.scanInterfaces();

        List<InetAddress> listAddresses = new ArrayList<InetAddress>();
        for (InterfaceDescription interfaceDescription : interfaces) {
            listAddresses.addAll(interfaceDescription.getInetAddresses());
        }

        metadata.setAddresses(
                listAddresses.toArray(new InetAddress[listAddresses.size()]));

        UUID hostId = null;
        // If HostIdAlreadyInUseException is thrown it will loop forever
        while (hostId == null) {
            try {
                hostId = HostIdGenerator.getHostId(configuration, zkManager);
                if (hostId != null) {
                    String hostName;
                    try {
                        hostName = InetAddress.getLocalHost().getHostName();
                    } catch (UnknownHostException e) {
                        hostName = "UNKNOWN";
                    }
                    metadata.setName(hostName);
                    zkManager.makeAlive(hostId, metadata);
                    break;
                }
            } catch (HostIdAlreadyInUseException e) {
                // The ID is already in use, wait. It could be that the ephemeral
                // node has not been deleted yet (if the host just crashed)
                log.warn("Host Id already in use.", e);
                // Reset the hostId to null to continue looping
                hostId = null;
            }
            Thread.sleep(configuration.getWaitTimeForUniqueHostId());
        }
        return hostId;
    }

    public UUID getHostId() {
        return hostId;
    }
}
