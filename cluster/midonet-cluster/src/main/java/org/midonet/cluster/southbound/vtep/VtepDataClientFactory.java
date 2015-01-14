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
package org.midonet.cluster.southbound.vtep;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Subscription;
import rx.functions.Action1;

import org.midonet.cluster.data.vtep.VtepConfigException;
import org.midonet.cluster.data.vtep.VtepConnection;
import org.midonet.cluster.data.vtep.VtepDataClient;
import org.midonet.cluster.data.vtep.VtepStateException;
import org.midonet.cluster.data.vtep.model.VtepEndPoint;
import org.midonet.packets.IPv4Addr;
import org.midonet.vtep.OvsdbVtepDataClient;

public class VtepDataClientFactory {

    /**
     * An entry for a VTEP data client in the clients set. Provides a custom
     * state changed handler for each client.
     */
    private final class Entry {
        private final OvsdbVtepDataClient client;
        private final Subscription subscription;

        private Entry(OvsdbVtepDataClient client) {
            this.client = client;
            this.subscription = client.observable()
                .subscribe(new Action1<VtepConnection.State$.Value>() {
                    @Override
                    public void call(VtepConnection.State$.Value state) {
                        if (state == VtepConnection.State$.MODULE$.DISPOSED())
                            onClientDisposed(Entry.this);
                    }
                });
        }
    }

    private static final Logger log =
        LoggerFactory.getLogger(VtepDataClientFactory.class);

    private static final int CONNECT_ATTEMPTS_ON_STATE_EXCEPTION = 3;
    private static final int DELAY_MS_ON_RETRY = 100;

    private final Map<VtepEndPoint, Entry> clients = new HashMap<>();

    private boolean disposed = false;

    public VtepDataClientFactory() {
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
        throws VtepStateException, VtepConfigException {

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
            OvsdbVtepDataClient client = new OvsdbVtepDataClient(
                endPoint, DELAY_MS_ON_RETRY, CONNECT_ATTEMPTS_ON_STATE_EXCEPTION);
            entry = new Entry(client);
            clients.put(endPoint, entry);
        } else {
            log.debug("Using existing client for VTEP {}", endPoint);
        }

        entry.client.connect(userId);
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
    }

    /**
     * Handles the state changes for a VTEP client by removing the entry for
     * this end point and un-subscribe from the client.
     *
     * @param entry The VTEP client entry.
     */
    private synchronized void onClientDisposed(Entry entry) {
        log.info("Disposing VTEP client {}", entry.client);
        if (clients.remove(entry.client.endPoint()) == entry) {
            entry.subscription.unsubscribe();
            log.debug("Disposed VTEP client {}", entry.client);
        } else {
            clients.put(entry.client.endPoint(), entry);
            log.warn("Internal error: unknown VTEP client {} was disposed",
                     entry.client);
        }
    }
}

