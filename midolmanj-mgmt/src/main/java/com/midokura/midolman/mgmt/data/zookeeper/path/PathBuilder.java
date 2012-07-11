/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.path;

import java.util.UUID;

/**
 * This class was created to have all state classes share the Zk path
 * information.
 */
public class PathBuilder {

    private final String basePath;
    public static final String BRIDGE_NAMES_PATH = "bridge-names";
    public static final String BRIDGES_PATH = "bridges";
    public static final String CHAIN_NAMES_PATH = "chain-names";
    public static final String CHAINS_PATH = "chains";
    public static final String ROUTER_NAMES_PATH = "router-names";
    public static final String ROUTERS_PATH = "routers";
    public static final String TENANTS_PATH = "tenants";
    private static final Object PORT_GROUPS_PATH = "port_groups";
    private static final Object PORT_GROUP_NAMES_PATH = "port_group-names";

    /**
     * Constructor
     *
     * @param basePath
     *            Base path of Zk.
     */
    public PathBuilder(String basePath) {
        if (basePath == null) {
            basePath = "";
        }
        this.basePath = basePath;
    }

    /**
     * @return The base path.
     */
    public String getBasePath() {
        return this.basePath;
    }

    /**
     * Get ZK bridge path.
     *
     * @param id
     *            Bridge UUID
     * @return /bridges/bridgeId
     */
    public String getBridgePath(UUID id) {
        return new StringBuilder(getBridgesPath()).append("/").append(id)
                .toString();
    }

    /**
     * Get ZK bridges path.
     *
     * @return /bridges
     */
    public String getBridgesPath() {
        return new StringBuilder(basePath).append("/").append(BRIDGES_PATH)
                .toString();
    }

    /**
     * Get ZK port group path.
     *
     * @return /port_groups/groupId
     */
    public String getPortGroupPath(UUID id) {
        return new StringBuilder(getPortGroupsPath()).append("/").append(id)
                .toString();
    }

    /**
     * Get ZK port groups path.
     *
     * @return /port_groups
     */
    public String getPortGroupsPath() {
        return new StringBuilder(basePath).append("/").append(PORT_GROUPS_PATH)
                .toString();
    }

    /**
     * Get ZK router path.
     *
     * @param id
     *            Router UUID
     * @return /routers/routerId
     */
    public String getRouterPath(UUID id) {
        return new StringBuilder(getRoutersPath()).append("/").append(id)
                .toString();
    }

    /**
     * Get ZK router path.
     *
     * @return /routers
     */
    public String getRoutersPath() {
        return new StringBuilder(basePath).append("/").append(ROUTERS_PATH)
                .toString();
    }

    /**
     * Get ZK tenant port group name path.
     *
     * @param tenantId
     *            Tenant UUID
     * @return /tenants/tenantId/port_group-names/name
     */
    public String getTenantPortGroupNamePath(String tenantId, String name) {
        return new StringBuilder(getTenantPortGroupNamesPath(tenantId))
                .append("/").append(name).toString();
    }

    /**
     * Get ZK tenant port group names path.
     *
     * @param tenantId
     *            Tenant UUID
     * @return /tenant/tenantId/port_group-names
     */
    public String getTenantPortGroupNamesPath(String tenantId) {
        return new StringBuilder(getTenantPath(tenantId.toString()))
                .append("/").append(PORT_GROUP_NAMES_PATH).toString();
    }

    /**
     * Get ZK tenant chain name path.
     *
     * @param tenantId
     *            Tenant UUID
     * @return /tenants/tenantId/chain-names/name
     */
    public String getTenantChainNamePath(String tenantId, String name) {
        return new StringBuilder(getTenantChainNamesPath(tenantId)).append("/")
                .append(name).toString();
    }

    /**
     * Get ZK tenant chain-names path.
     *
     * @param tenantId
     *            Tenant UUID
     * @return /tenant/tenantId/chain-names
     */
    public String getTenantChainNamesPath(String tenantId) {
        return new StringBuilder(getTenantPath(tenantId.toString()))
                .append("/").append(CHAIN_NAMES_PATH).toString();
    }

    /**
     * Get ZK tenant chain path.
     *
     * @param tenantId
     *            Tenant UUID
     * @return /tenants/tenantId/chains/chainId
     */
    public String getTenantChainPath(String tenantId, UUID chainId) {
        return new StringBuilder(getTenantChainsPath(tenantId)).append("/")
                .append(chainId).toString();
    }

    /**
     * Get ZK tenant chains path.
     *
     * @param tenantId
     *            Tenant UUID
     * @return /tenants/tenantId/chains
     */
    public String getTenantChainsPath(String tenantId) {
        return new StringBuilder(getTenantPath(tenantId.toString()))
                .append("/").append(CHAINS_PATH).toString();
    }

    /**
     * Get ZK tenant bridge name path.
     *
     * @return /tenants/tenantId/bridge-names/name
     */
    public String getTenantBridgeNamePath(String tenantId, String name) {
        return new StringBuilder(getTenantBridgeNamesPath(tenantId))
                .append("/").append(name).toString();
    }

    /**
     * Get ZK tenant bridge names path.
     *
     * @return /tenants/tenantId/bridge-names
     */
    public String getTenantBridgeNamesPath(String tenantId) {
        return new StringBuilder(getTenantPath(tenantId)).append("/")
                .append(BRIDGE_NAMES_PATH).toString();
    }

    /**
     * Get ZK tenant bridge path.
     *
     * @param tenantId
     *            Tenant UUID
     * @param bridgeId
     *            Bridge UUID
     * @return /tenants/tenantId/bridges/bridgeId
     */
    public String getTenantBridgePath(String tenantId, UUID bridgeId) {
        return new StringBuilder(getTenantBridgesPath(tenantId)).append("/")
                .append(bridgeId).toString();
    }

    /**
     * Get ZK tenant bridge path.
     *
     * @param tenantId
     *            Tenant UUID
     * @return /tenants/tenantId/bridges
     */
    public String getTenantBridgesPath(String tenantId) {
        return new StringBuilder(getTenantPath(tenantId)).append("/")
                .append(BRIDGES_PATH).toString();
    }

    /**
     * Get ZK tenant path.
     *
     * @param id
     *            Tenant ID
     * @return /tenants/tenantId
     */
    public String getTenantPath(String id) {
        return new StringBuilder(getTenantsPath()).append("/").append(id)
                .toString();
    }

    /**
     * Get ZK tenant router name path.
     *
     * @return /tenants/tenantId/router-names/name
     */
    public String getTenantRouterNamePath(String tenantId, String name) {
        return new StringBuilder(getTenantRouterNamesPath(tenantId))
                .append("/").append(name).toString();
    }

    /**
     * Get ZK tenant router names path.
     *
     * @return /tenants/tenantId/router-names
     */
    public String getTenantRouterNamesPath(String tenantId) {
        return new StringBuilder(getTenantPath(tenantId)).append("/")
                .append(ROUTER_NAMES_PATH).toString();
    }

    /**
     * Get ZK tenant router path.
     *
     * @param tenantId
     *            Tenant UUID
     * @param routerId
     *            Router UUID
     * @return /tenants/tenantId/routers/routerId
     */
    public String getTenantRouterPath(String tenantId, UUID routerId) {
        return new StringBuilder(getTenantRoutersPath(tenantId)).append("/")
                .append(routerId).toString();
    }

    /**
     * Get ZK tenant router path.
     *
     * @param tenantId
     *            Tenant ID
     * @return /tenants/tenantId/routers
     */
    public String getTenantRoutersPath(String tenantId) {
        return new StringBuilder(getTenantPath(tenantId)).append("/")
                .append(ROUTERS_PATH).toString();
    }

    /**
     * Get ZK tenant path.
     *
     * @return /tenants
     */
    public String getTenantsPath() {
        return new StringBuilder(basePath).append("/").append(TENANTS_PATH)
                .toString();
    }
}
