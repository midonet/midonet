/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.tools.ovsdb.cli;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import org.opendaylight.controller.sal.connection.ConnectionConstants;
import org.opendaylight.controller.sal.core.Node;
import org.opendaylight.ovsdb.lib.table.internal.Table;
import org.opendaylight.ovsdb.lib.table.vtep.Physical_Switch;
import org.opendaylight.ovsdb.plugin.ConfigurationService;
import org.opendaylight.ovsdb.plugin.ConnectionService;
import org.opendaylight.ovsdb.plugin.InventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A wrapper for the OVS-DB client methods.
 */
public final class OvsDbDataClient extends ConfigurationService {

    private static final Logger log =
        LoggerFactory.getLogger(OvsDbDataClient.class);

    private static final String VTEP_NODE_NAME= "vtep";
    private static final int CON_TIMEOUT_MILLIS = 5000;

    private final ConnectionService conSrv;
    private final InventoryService invSrv;

    private Node node = null;

    public OvsDbDataClient() {
        conSrv = new ConnectionService();
        invSrv = new InventoryService();
    }

    /**
     * Connects to the OVS-DB database.
     * @param address The IP address.
     * @param port The port.
     */
    public synchronized void connect(String address, String port) {
        if (null != node) {
            log.error("The OVS-DB client is already connected.");
        }

        log.info("Connecting to OVS-DB server {}:{}.", address, port);

        Map<ConnectionConstants, String> params = new HashMap<>();
        params.put(ConnectionConstants.ADDRESS, address);
        params.put(ConnectionConstants.PORT, port);

        conSrv.setInventoryServiceInternal(invSrv);
        setInventoryServiceInternal(invSrv);
        setConnectionServiceInternal(conSrv);

        node = conSrv.connect(VTEP_NODE_NAME, params);

        log.info("Connected to OVS-DB server {}:{}.", address, port);

        long timeoutAt = System.currentTimeMillis() + CON_TIMEOUT_MILLIS;
        while (!this.isReady() && System.currentTimeMillis() < timeoutAt) {
            log.debug("Waiting for inventory service initialization");
            try {
                Thread.sleep(CON_TIMEOUT_MILLIS / 10);
            } catch (InterruptedException e) {
                log.error("Interrupted while waiting for service init.");
                Thread.interrupted();
            }
        }
    }

    /**
     * Disconnects from the OVS-DB database.
     */
    public synchronized void disconnect() {
        if (null == node) {
            log.error("The OVS-DB client is not connected.");
        }

        log.info("Disconnecting from the OVD-SB server.");

        conSrv.disconnect(node);
        node = null;

        log.info("Disconnected from the OVS-DB server.");
    }

    public boolean isReady() {
        Map<String, ConcurrentMap<String, Table<?>>> cache =
            this.conSrv.getInventoryServiceInternal().getCache(node);
        if (cache == null) {
            return false;
        }
        Map<String, Table<?>> psTableCache =
            cache.get(Physical_Switch.NAME.getName());

        return psTableCache != null && !psTableCache.isEmpty();
    }

    /**
     * Gets the database node.
     * @return The node.
     */
    public Node getNode() {
        return node;
    }
}
