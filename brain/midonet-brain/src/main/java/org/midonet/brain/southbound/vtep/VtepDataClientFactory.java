/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.vtep;

import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.UUID;

import org.opendaylight.ovsdb.plugin.ConfigurationService;
import org.opendaylight.ovsdb.plugin.ConnectionService;
import org.opendaylight.ovsdb.plugin.InventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Subscription;
import rx.functions.Action1;

import org.midonet.packets.IPv4Addr;

public class VtepDataClientFactory {

    /**
     * An entry for a VTEP data client in the clients set. Provides a custom
     * state changed handler for each client.
     */
    private final class Entry {
        private final VtepDataClientImpl client;
        private final Subscription subscription;

        private Entry(VtepDataClientImpl client) {
            this.client = client;
            this.subscription = client.stateObservable()
                .subscribe(new Action1<VtepDataClient.State>() {
                    @Override
                    public void call(VtepDataClient.State state) {
                        if (VtepDataClient.State.DISPOSED == state) {
                            onClientDisposed(Entry.this);
                        }
                    }
                });
        }
    }

    private static final Logger log =
        LoggerFactory.getLogger(VtepDataClientFactory.class);

    private static final int CONNECT_ATTEMPTS_ON_STATE_EXCEPTION = 3;

    protected static final Timer timer = new Timer(true);

    private final ConnectionService connectionService;
    private final ConfigurationService configurationService;

    private final Map<VtepEndPoint, Entry> clients = new HashMap<>();

    private boolean disposed = false;

    public VtepDataClientFactory() {
        connectionService = new ConnectionService();
        configurationService = new ConfigurationService();
        InventoryService inventoryService = new InventoryService();

        connectionService.setInventoryServiceInternal(inventoryService);
        configurationService.setInventoryServiceInternal(inventoryService);
        configurationService.setConnectionServiceInternal(connectionService);
    }

    /**
     * Connects to the VTEP with the specified IP address and transport port
     * (end-point). The method returns a connection-aware VTEP data client.
     *
     * If a previous connection to the same end-point already exists, the
     * method returns the client instance corresponding to that connection.
     * Otherwise, the method creates a new client instance.
     *
     * In both cases, the client attempts to connect to the VTEP and initialize
     * the VTEP table cache, if the client was not already connected. After
     * executing this method, it is possible that the client is not connected,
     * if the connection failed.
     *
     * The caller can use the client <code>isConnected</code> and
     * <code>awaitConnected</code> methods to determine the state of the
     * connection and to wait for the client to be connected.
     *
     * @param mgmtIp The VTEP management IP address.
     * @param mgmtPort The VTEP management port.
     * @param userId The user for this connection.
     * @return The VTEP data client.
     * @throws VtepStateException The client could not connect because of
     * an invalid service state after a number of retries.
     */
    public synchronized VtepDataClient connect(IPv4Addr mgmtIp, int mgmtPort,
                                               UUID userId)
        throws VtepStateException {

        if (null == mgmtIp)
            throw new IllegalArgumentException("mgmtIp cannot be null");
        if (null == userId)
            throw new IllegalArgumentException("userId cannot be null");
        if (disposed)
            throw new IllegalStateException(
                "The VTEP provider has been disposed");

        // Create the end-point for this client.
        VtepEndPoint endPoint = new VtepEndPoint(mgmtIp, mgmtPort);

        // Get or create the VTEP data client.
        Entry entry = clients.get(endPoint);
        if (null == entry) {
            log.debug("Creating new client for VTEP {}", endPoint);

            VtepDataClientImpl client = new VtepDataClientImpl(
                endPoint, configurationService, connectionService);
            entry = new Entry(client);
            clients.put(endPoint, entry);
        } else {
            log.debug("Using existing client for VTEP {}", endPoint);
        }

        // Try to connect the client.
        for (int attempt = 0; true; ) {
            try {
                log.debug("Connecting to VTEP {} (attempt {})",
                          endPoint, attempt);

                entry.client.connect(userId);
                break;
            } catch (VtepStateException e) {
                if (++attempt == CONNECT_ATTEMPTS_ON_STATE_EXCEPTION)
                    throw e;
            }
        }

        return entry.client;
    }

    /**
     * Disposes this VTEP data client provider by disconnecting all clients.
     */
    public synchronized void dispose() {
        if (disposed)
            throw new IllegalStateException(
                "The VTEP provider has been disposed");

        log.debug("Disposing VTEP client provider");
        disposed = true;

        // Dispose all clients.
        for (Entry entry : clients.values()) {
            log.info("Disposing VTEP client {}", entry.client);
            entry.subscription.unsubscribe();
            entry.client.dispose();
        }
        clients.clear();

        // Destroy the connection service to shutdown the netty event loop.
        connectionService.destroy();
    }

    /**
     * Handles the state changes for a VTEP client by removing the entry for
     * this end point and un-subscribe from the client.
     *
     * @param entry The VTEP client entry.
     */
    private synchronized void onClientDisposed(Entry entry) {
        log.info("Disposing VTEP client {}", entry.client);
        if (clients.remove(entry.client.endPoint) == entry) {
            entry.subscription.unsubscribe();
            log.debug("Disposed VTEP client {}", entry.client);
        } else {
            clients.put(entry.client.endPoint, entry);
            log.warn("Internal error: unknown VTEP client {} was disposed",
                     entry.client);
        }
    }
}

