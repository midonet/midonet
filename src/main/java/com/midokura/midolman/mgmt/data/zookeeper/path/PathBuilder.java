/*
 * @(#)PathBuilder        1.6 11/12/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.path;

import java.util.UUID;

import com.midokura.midolman.mgmt.rest_api.core.ChainTable;

/**
 * StringBuilder wrapper class that assists in building MidoNet Mgmt ZK paths.
 *
 * @version 1.6 15 Dec 2011
 * @author Ryu Ishimoto
 */
public class PathBuilder {

    public static final String BRIDGE_NAMES_PATH = "/bridge-names";
    public static final String BRIDGES_PATH = "/bridges";
    public static final String CHAIN_NAMES_PATH = "/chain-names";
    public static final String CHAINS_PATH = "/chains";
    public static final String PORTS_PATH = "/ports";
    public static final String ROUTER_NAMES_PATH = "/router-names";
    public static final String ROUTERS_PATH = "/routers";
    public static final String TABLES_PATH = "/tables";
    public static final String TENANTS_PATH = "/tenants";
    public static final String VIFS_PATH = "/vifs";

    /**
     * ZK path. It is protected in case child classes want to implement
     * additional paths.
     */
    protected final StringBuilder path;

    /**
     * Constructor
     *
     * @param root
     *            Root ZK path. Null sets the root path to an emtpy string.
     */
    public PathBuilder(String root) {
        if (root == null) {
            root = "";
        }
        this.path = new StringBuilder(root);
    }

    private PathBuilder appendPathAndId(String newPath, String id) {
        path.append(newPath);
        if (id != null) {
            path.append("/").append(id);
        }
        return this;
    }

    /**
     * Set the bridge names path("/bridge-names").
     *
     * @return PathBuilder object.
     */
    public PathBuilder bridgeNames() {
        return appendPathAndId(BRIDGE_NAMES_PATH, null);
    }

    /**
     * Set the bridge names path("/bridge-names/name").
     *
     * @param name
     *            Name of the bridge
     * @return PathBuilder object.
     */
    public PathBuilder bridgeNames(String name) {
        return appendPathAndId(BRIDGE_NAMES_PATH, name);
    }

    /**
     * Set the bridges path("/bridges").
     *
     * @return PathBuilder object.
     */
    public PathBuilder bridges() {
        return appendPathAndId(BRIDGES_PATH, null);
    }

    /**
     * Set the bridges path("/bridges/id").
     *
     * @param id
     *            ID of the bridge
     * @return PathBuilder object.
     */
    public PathBuilder bridges(UUID id) {
        return appendPathAndId(BRIDGES_PATH, id.toString());
    }

    /**
     * Build the path with the currently appended paths.
     *
     * @return The path string built by StringBuilder.
     */
    public String build() {
        return path.toString();
    }

    /**
     * Set the chain names path("/chain-names").
     *
     * @return PathBuilder object.
     */
    public PathBuilder chainNames() {
        return appendPathAndId(CHAIN_NAMES_PATH, null);
    }

    /**
     * Set the chain names path("/chain-names/name").
     *
     * @param name
     *            Name of the chain.
     * @return PathBuilder object.
     */
    public PathBuilder chainNames(String name) {
        return appendPathAndId(CHAIN_NAMES_PATH, name);
    }

    /**
     * Set the chains path("/chains").
     *
     * @return PathBuilder object.
     */
    public PathBuilder chains() {
        return appendPathAndId(CHAINS_PATH, null);
    }

    /**
     * Set the chains path("/chains/id").
     *
     * @param id
     *            ID of the chain.
     * @return PathBuilder object.
     */
    public PathBuilder chains(UUID id) {
        return appendPathAndId(CHAINS_PATH, id.toString());
    }

    /**
     * Set the ports path("/ports").
     *
     * @return PathBuilder object.
     */
    public PathBuilder ports() {
        return appendPathAndId(PORTS_PATH, null);
    }

    /**
     * Set the ports path("/ports/id").
     *
     * @param id
     *            ID of the port.
     * @return PathBuilder object.
     */
    public PathBuilder ports(UUID id) {
        return appendPathAndId(PORTS_PATH, id.toString());
    }

    /**
     * Set the router-names path("/router-names").
     *
     * @return PathBuilder object.
     */
    public PathBuilder routerNames() {
        return appendPathAndId(ROUTER_NAMES_PATH, null);
    }

    /**
     * Set the router-name path("/router-names/name").
     *
     * @param name
     *            Name of the router.
     * @return PathBuilder object.
     */
    public PathBuilder routerNames(String name) {
        return appendPathAndId(ROUTER_NAMES_PATH, name);
    }

    /**
     * Set the routers path("/routers").
     *
     * @return PathBuilder object.
     */
    public PathBuilder routers() {
        return appendPathAndId(ROUTERS_PATH, null);
    }

    /**
     * Set the routers path("/routers/id").
     *
     * @param id
     *            ID of the router.
     * @return PathBuilder object.
     */
    public PathBuilder routers(UUID id) {
        return appendPathAndId(ROUTERS_PATH, id.toString());
    }

    /**
     * Set the table names path("/tables").
     *
     * @return PathBuilder object.
     */
    public PathBuilder tables() {
        return appendPathAndId(TABLES_PATH, null);
    }

    /**
     * Set the table names path("/tables/name").
     *
     * @param name
     *            Name of the table.
     * @return PathBuilder object.
     */
    public PathBuilder tables(String name) {
        return appendPathAndId(TABLES_PATH, name);
    }

    /**
     * Set the table names path("/tables/name").
     *
     * @param name
     *            Name of the table.
     * @return PathBuilder object.
     */
    public PathBuilder tables(ChainTable name) {
        return tables(name.toString());
    }

    /**
     * Set the tenants path("/tenants").
     *
     * @return PathBuilder object.
     */

    public PathBuilder tenants() {
        return appendPathAndId(TENANTS_PATH, null);
    }

    /**
     * Set the tenants path("/tenants").
     *
     * @param id
     *            ID of the tenant.
     * @return PathBuilder object.
     */
    public PathBuilder tenants(String id) {
        return appendPathAndId(TENANTS_PATH, id);
    }

    /**
     * Set the VIF path("/vifs").
     *
     * @return PathBuilder object.
     */
    public PathBuilder vifs() {
        return appendPathAndId(VIFS_PATH, null);
    }

    /**
     * Set the VIF path("/vifs/id").
     *
     * @param id
     *            ID of the VIF.
     * @return PathBuilder object.
     */
    public PathBuilder vifs(UUID id) {
        return appendPathAndId(VIFS_PATH, id.toString());
    }

}
