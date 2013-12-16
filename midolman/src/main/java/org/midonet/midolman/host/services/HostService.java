/*
 * Copyright 2012 Midokura PTE LTD.
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.midolman.host.services;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

import com.google.common.util.concurrent.AbstractService;
import com.google.inject.Inject;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.ZkManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.host.HostIdGenerator;
import org.midonet.midolman.host.HostIdGenerator.HostIdAlreadyInUseException;
import org.midonet.midolman.host.HostIdGenerator.PropertiesFileNotWritableException;
import org.midonet.midolman.host.HostInterfaceWatcher;
import org.midonet.midolman.host.commands.executors.HostCommandWatcher;
import org.midonet.midolman.host.config.HostConfig;
import org.midonet.midolman.host.interfaces.InterfaceDescription;
import org.midonet.midolman.host.scanner.InterfaceScanner;
import org.midonet.midolman.host.state.HostDirectory;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.services.HostIdProviderService;
import org.midonet.midolman.state.StateAccessException;

/**
 * Host internal service.
 * <p/>
 * It starts and stops the host service.
 * TODO: need to try to reattach the zk session so we can recover the host state.
 */
public class HostService extends AbstractService
    implements HostIdProviderService {

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
    private HostZkManager hostZkManager;

    @Inject
    private ZkManager zkManager;

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
            throw new RuntimeException("Could not start Midolman", e);
        }
        log.info("Midolman host agent started.");

    }

    @Override
    protected void doStop() {

        log.info("Stopping Midolman host agent.");
        try {
            scanner.shutDownNow();

            // tell the watcher thread to stop
            interfaceWatcher.stop();

            // wait for the thread to finish running
            if (watcherThread != null ) {
                LockSupport.unpark(watcherThread);
                watcherThread.join();
            }

            // disconnect from zookeeper.
            // this will cause the ephemeral nodes to disappear.
            zkManager.disconnect();

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
            InterruptedException, SerializationException {

        log.debug("Identifying host");
        // Try to get the host Id
        HostDirectory.Metadata metadata = new HostDirectory.Metadata();

        // Retrieve the interfaces and store the addresses in the metadata
        List<InetAddress> listAddresses = new ArrayList<InetAddress>();
        for (InterfaceDescription info: scanner.scanInterfaces()) {
            listAddresses.addAll(info.getInetAddresses());
        }

        metadata.setAddresses(
                listAddresses.toArray(new InetAddress[listAddresses.size()]));

        UUID hostId = null;
        // If HostIdAlreadyInUseException is thrown it will loop forever
        while (hostId == null) {
            try {
                hostId = HostIdGenerator.getHostId(configuration, hostZkManager);
                if (hostId != null) {
                    String hostName;
                    try {
                        hostName = InetAddress.getLocalHost().getHostName();
                    } catch (UnknownHostException e) {
                        hostName = "UNKNOWN";
                    }
                    metadata.setName(hostName);
                    hostZkManager.makeAlive(hostId, metadata);
                    hostZkManager.setHostVersion(hostId);
                    break;
                }
            } catch (HostIdAlreadyInUseException e) {
                // The ID is already in use, wait. It could be that the ephemeral
                // node has not been deleted yet (if the host just crashed)
                log.warn("Host Id already in use. Waiting for it to be released.", e);
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
