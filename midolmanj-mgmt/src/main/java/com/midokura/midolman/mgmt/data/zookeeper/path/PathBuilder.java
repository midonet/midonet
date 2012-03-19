/*
 * @(#)PathBuilder        1.6 20/12/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.path;

import java.util.UUID;

import com.midokura.midolman.mgmt.rest_api.core.ChainTable;

/**
 * This class was created to have all state classes share the Zk path
 * information.
 *
 * @version 1.6 19 Sept 2011
 * @author Ryu Ishimoto
 */
public class PathBuilder {

    private final String basePath;
    public static final String BRIDGE_NAMES_PATH = "bridge-names";
    public static final String BRIDGES_PATH = "bridges";
    public static final String CHAIN_NAMES_PATH = "chain-names";
    public static final String CHAINS_PATH = "chains";
    public static final String PORTS_PATH = "ports";
    public static final String ROUTER_NAMES_PATH = "router-names";
    public static final String ROUTERS_PATH = "routers";
    public static final String TABLES_PATH = "tables";
    public static final String TENANTS_PATH = "tenants";
    public static final String VIFS_PATH = "vifs";

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
     * Get ZK path to a bridge's routers.
     *
     * @param id
     *            Bridge UUID
     * @return /bridges/bridgeId/routers
     */
    public String getBridgeRoutersPath(UUID id) {
        return new StringBuilder(getBridgePath(id)).append("/")
                .append(ROUTERS_PATH).toString();
    }

    /**
     * Get ZK path to a bridge's connected router.
     *
     * @param bridgeId
     *            Bridge UUID
     * @param routerId
     *            Router UUID
     * @return /bridges/bridgeId/routers/routerId
     */
    public String getBridgeRouterPath(UUID bridgeId, UUID routerId) {
        return new StringBuilder(getBridgeRoutersPath(bridgeId)).append("/")
                .append(routerId).toString();
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
     * Get ZK chain path.
     *
     * @return /chains/chainId
     */
    public String getChainPath(UUID id) {
        return new StringBuilder(getChainsPath()).append("/").append(id)
                .toString();
    }

    /**
     * Get ZK chains path.
     *
     * @return /chains
     */
    public String getChainsPath() {
        return new StringBuilder(basePath).append("/").append(CHAINS_PATH)
                .toString();
    }

    /**
     * Get ZK port path.
     *
     * @param id
     *            Port ID.
     * @return /ports/portId
     */
    public String getPortPath(UUID id) {
        return new StringBuilder(getPortsPath()).append("/").append(id)
                .toString();
    }

    /**
     * Get ZK port path.
     *
     * @return /ports
     */
    public String getPortsPath() {
        return new StringBuilder(basePath).append("/").append(PORTS_PATH)
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
     * Get ZK router peer router path.
     *
     * @param routerId
     *            Router UUID
     * @return /routers/routerId/routers/routerId
     */
    public String getRouterRouterPath(UUID routerId, UUID peerRouterId) {
        return new StringBuilder(getRouterRoutersPath(routerId)).append("/")
                .append(peerRouterId).toString();
    }

    /**
     * Get ZK router peer router path.
     *
     * @param routerId
     *            Router UUID
     * @return /routers/routerId/routers
     */
    public String getRouterRoutersPath(UUID routerId) {
        return new StringBuilder(getRouterPath(routerId)).append("/")
                .append(ROUTERS_PATH).toString();
    }

    /**
     * Get ZK router linked bridge path.
     *
     * @param routerId
     *            Router UUID
     * @param bridgeId
     *            Bridge UUID
     * @return /routers/routerId/bridges/bridgeId
     */
    public String getRouterBridgePath(UUID routerId, UUID bridgeId) {
        return new StringBuilder(getRouterBridgesPath(routerId)).append("/")
                .append(bridgeId).toString();
    }

    /**
     * Get ZK router linked bridges path.
     *
     * @param routerId
     *            Router UUID
     * @return /routers/routerId/bridges
     */
    public String getRouterBridgesPath(UUID routerId) {
        return new StringBuilder(getRouterPath(routerId)).append("/")
                .append(BRIDGES_PATH).toString();
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
     * Get ZK router router table chain path.
     *
     * @param id
     *            Router UUID
     * @return /routers/routerId/tables/tableName/chain-names/chainName
     */
    public String getRouterTableChainNamePath(UUID routerId,
            ChainTable tableName, String chainName) {
        return new StringBuilder(getRouterTableChainNamesPath(routerId,
                tableName)).append("/").append(chainName).toString();
    }

    /**
     * Get ZK router router table chain names path.
     *
     * @param id
     *            Router UUID
     * @return /routers/routerId/tables/tableName/chain-names
     */
    public String getRouterTableChainNamesPath(UUID routerId,
            ChainTable tableName) {
        return new StringBuilder(getRouterTablePath(routerId, tableName))
                .append("/").append(CHAIN_NAMES_PATH).toString();
    }

    /**
     * Get ZK router router table chain path.
     *
     * @param id
     *            Router UUID
     * @return /routers/routerId/tables/tableName/chains/chainId
     */
    public String getRouterTableChainPath(UUID routerId, ChainTable tableName,
            UUID chainId) {
        return new StringBuilder(getRouterTableChainsPath(routerId, tableName))
                .append("/").append(chainId).toString();
    }

    /**
     * Get ZK router table chains path.
     *
     * @param id
     *            Router UUID
     * @return /routers/routerId/tables/tableName/chains
     */
    public String getRouterTableChainsPath(UUID id, ChainTable tableName) {
        return new StringBuilder(getRouterTablePath(id, tableName)).append("/")
                .append(CHAINS_PATH).toString();
    }

    /**
     * Get ZK router tables path.
     *
     * @param id
     *            Router UUID
     * @return /routers/routerId/tables/table_name
     */
    public String getRouterTablePath(UUID id, ChainTable tableName) {
        return new StringBuilder(getRouterTablesPath(id)).append("/")
                .append(tableName).toString();
    }

    /**
     * Get ZK router tables path.
     *
     * @param id
     *            Router UUID
     * @return /routers/routerId/tables
     */
    public String getRouterTablesPath(UUID id) {
        return new StringBuilder(getRoutersPath()).append("/").append(id)
                .append("/").append(TABLES_PATH).toString();
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
     * @param routerId
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

    /**
     * Get VIF path.
     *
     * @return /vifs/vifId
     */
    public String getVifPath(UUID vifId) {
        return new StringBuilder(getVifsPath()).append("/").append(vifId)
                .toString();
    }

    /**
     * Get VIF path.
     *
     * @return /vifs
     */
    public String getVifsPath() {
        return new StringBuilder(basePath).append("/").append(VIFS_PATH)
                .toString();
    }
}
