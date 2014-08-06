/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.host.services;

import java.lang.reflect.Array;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.google.common.util.concurrent.AbstractService;
import com.google.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.Subscription;
import org.midonet.config.HostIdGenerator;
import org.midonet.midolman.host.commands.executors.HostCommandWatcher;
import org.midonet.midolman.host.config.HostConfig;
import org.midonet.midolman.host.interfaces.InterfaceDescription;
import org.midonet.midolman.host.scanner.InterfaceScanner;
import org.midonet.midolman.host.state.HostDirectory;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.host.updater.InterfaceDataUpdater;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.services.HostIdProviderService;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.netlink.Callback;
import org.midonet.netlink.exceptions.NetlinkException;

/**
 * Host internal service.
 * It starts and stops the host service.
 * TODO: need to try to reattach the zk session so we can recover the host state.
 */
public class HostService extends AbstractService
    implements HostIdProviderService {

    private static final Logger log = LoggerFactory
            .getLogger(HostService.class);

    private UUID hostId;

    @Inject
    HostConfig configuration;

    @Inject
    private HostCommandWatcher cmdWatcher;

    @Inject
    InterfaceScanner scanner;

    @Inject
    InterfaceDataUpdater interfaceDataUpdater;

    @Inject
    private HostZkManager hostZkManager;

    @Inject
    private ZkManager zkManager;

    public final long epoch = System.currentTimeMillis();

    public static class HostIdAlreadyInUseException extends Exception {

        private static final long serialVersionUID = 1L;

        HostIdAlreadyInUseException(String message) {
            super(message);
        }
    }

    @Override
    protected void doStart() {

        log.info("Starting Midolman host agent.");
        try {
            identifyHostId();
            cmdWatcher.checkCommands(hostId);
            scanner.start();
            scanner.register(new Callback<Set<InterfaceDescription>>() {
                @Override
                public void onSuccess(Set<InterfaceDescription> data) {
                    interfaceDataUpdater.updateInterfacesData(
                            hostId, null, data);
                }

                @Override
                public void onError(NetlinkException e) {
                }
            });
            notifyStarted();
            log.info("Midolman host agent started.");
        } catch (Exception e) {
            log.error("HostService failed to start", e);
            notifyFailed(e);
        }
    }

    @Override
    protected void doStop() {
        log.info("Stopping Midolman host agent.");
        scanner.shutdown();

        // disconnect from zookeeper.
        // this will cause the ephemeral nodes to disappear.
        zkManager.disconnect();

        notifyStopped();
        log.info("Midolman host agent stopped.");
    }

    /**
     * Scans the host and identifies the host ID.
     *
     * @throws StateAccessException when problems reading data from ZK
     * @throws
     *    org.midonet.config.HostIdGenerator.PropertiesFileNotWritableException
     *    if the properties file can't be written
     * @throws InterruptedException
     */
    private void identifyHostId()
            throws StateAccessException,
                   HostIdGenerator.PropertiesFileNotWritableException,
                   InterruptedException, SerializationException,
                   HostIdAlreadyInUseException {

        log.debug("Identifying host");
        // Try to get the host Id
        HostDirectory.Metadata metadata = new HostDirectory.Metadata();

        metadata.setEpoch(epoch);

        // Retrieve the interfaces and store the addresses in the metadata
        ArrayList<InetAddress> listAddresses = new ArrayList<>();
        for (InterfaceDescription info : getInterfaces()) {
            listAddresses.addAll(info.getInetAddresses());
        }

        metadata.setAddresses(
                listAddresses.toArray(new InetAddress[listAddresses.size()]));

        try {
            metadata.setName(InetAddress.getLocalHost().getHostName());
        } catch (UnknownHostException e) {
            metadata.setName("UNKNOWN");
        }

        hostId = HostIdGenerator.getHostId(configuration);
        int retries = configuration.getRetriesForUniqueHostId();

        while (!create(hostId, metadata, hostZkManager) && --retries >= 0) {
            // The ID is already in use, wait. It could be that the ephemeral
            // node has not been deleted yet (if the host just crashed)
            log.warn("Host ID already in use. Waiting for it to be released.");

            Thread.sleep(configuration.getWaitTimeForUniqueHostId());
        }

        if (retries < 0) {
            log.error("Couldn't take ownership of the in-use host ID");
            throw new HostIdAlreadyInUseException(
                    "Host ID " + hostId + "appears to already be taken");
        }
    }

    private static boolean create(UUID id,
                                  HostDirectory.Metadata metadata,
                                  HostZkManager zkManager)
            throws StateAccessException, SerializationException {

        if (zkManager.exists(id)) {
            if (!metadata.isSameHost(zkManager.get(id))) {
                if (zkManager.isAlive(id))
                    return false;
            }
            zkManager.updateMetadata(id, metadata);
        } else {
            zkManager.createHost(id, metadata);
        }

        zkManager.makeAlive(id);
        zkManager.setHostVersion(id);

        return true;
    }

    public UUID getHostId() {
        return hostId;
    }

    @SuppressWarnings("unchecked")
    private Set<InterfaceDescription> getInterfaces() {
        final CountDownLatch latch = new CountDownLatch(1);
        final Set<InterfaceDescription>[] interfaces =
                (Set<InterfaceDescription>[]) Array.newInstance(Set.class, 1);

        Subscription s = scanner.register(new Callback<Set<InterfaceDescription>>() {
            @Override
            public void onSuccess(Set<InterfaceDescription> data) {
                interfaces[0] = data;
                latch.countDown();
            }

            @Override
            public void onError(NetlinkException e) { /* Not called */ }
        });

        try {
            latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException("Timeout while waiting for interfaces", e);
        }

        s.unsubscribe();
        return interfaces[0];
    }
}
