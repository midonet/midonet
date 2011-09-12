/*
 * @(#)ZkPathManager        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

import java.util.UUID;

/**
 * This class was created to have all state classes share the Zk path
 * information.
 * 
 * @version 1.6 08 Sept 2011
 * @author Ryu Ishimoto
 */
public class ZkPathManager {

    private String basePath = null;

    /**
     * Constructor.
     * 
     * @param basePath
     *            Base path of Zk.
     */
    public ZkPathManager(String basePath) {
        this.basePath = basePath;
    }

    /**
     * @return the basePath
     */
    public String getBasePath() {
        return basePath;
    }

    /**
     * @param basePath
     *            the basePath to set
     */
    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    /**
     * Get GRE path.
     * 
     * @return /gre
     */
    public String getGrePath() {
        return new StringBuilder(basePath).append("/gre").toString();
    }

    /**
     * Get GRE key path.
     * 
     * @return /gre/greKey
     */
    public String getGreKeyPath(int greKeyId) {
        return new StringBuilder(getGrePath()).append("/").append(greKeyId)
                .toString();
    }

    /**
     * Get ZK tenant path.
     * 
     * @return /tenants
     */
    public String getTenantsPath() {
        return new StringBuilder(basePath).append("/tenants").toString();
    }

    /**
     * Get ZK tenant path.
     * 
     * @param id
     *            Tenant UUID
     * @return /tenants/tenantId
     */
    public String getTenantPath(UUID id) {
        return new StringBuilder(getTenantsPath()).append("/").append(id)
                .toString();
    }

    /**
     * Get ZK bridges path.
     * 
     * @return /birdges
     */
    public String getBridgesPath() {
        return new StringBuilder(basePath).append("/bridges").toString();
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
     * Get ZK router path.
     * 
     * @return /routers
     */
    public String getRoutersPath() {
        return new StringBuilder(basePath).append("/routers").toString();
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
     * Get ZK tenant router path.
     * 
     * @param tenantId
     *            Tenant UUID
     * @return /tenants/tenantId/routers
     */
    public String getTenantRoutersPath(UUID tenantId) {
        return new StringBuilder(getTenantPath(tenantId)).append("/routers")
                .toString();
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
    public String getTenantRouterPath(UUID tenantId, UUID routerId) {
        return new StringBuilder(getTenantRoutersPath(tenantId)).append("/")
                .append(routerId).toString();
    }

    /**
     * Get ZK tenant bridge path.
     * 
     * @param tenantId
     *            Tenant UUID
     * @return /tenants/tenantId/bridges
     */
    public String getTenantBridgesPath(UUID tenantId) {
        return new StringBuilder(getTenantPath(tenantId)).append("/bridges")
                .toString();
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
    public String getTenantBridgePath(UUID tenantId, UUID bridgeId) {
        return new StringBuilder(getTenantBridgesPath(tenantId)).append("/")
                .append(bridgeId).toString();
    }

    /**
     * Get ZK port path.
     * 
     * @return /ports
     */
    public String getPortsPath() {
        return new StringBuilder(basePath).append("/ports").toString();
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
     * Get ZK router port path.
     * 
     * @param routerId
     *            Router UUID
     * @return /routers/routerId/ports
     */
    public String getRouterPortsPath(UUID routerId) {
        return new StringBuilder(getRouterPath(routerId)).append("/ports")
                .toString();
    }

    /**
     * Get ZK router port path.
     * 
     * @param routerId
     *            Router UUID
     * @param portId
     *            Port UUID.
     * @return /routers/routerId/ports/portId
     */
    public String getRouterPortPath(UUID routerId, UUID portId) {
        return new StringBuilder(getRouterPortsPath(routerId)).append("/")
                .append(portId).toString();
    }

    /**
     * Get ZK bridge port path.
     * 
     * @param bridgeId
     *            Bridge UUID
     * @return /bridges/bridgeId/ports
     */
    public String getBridgePortsPath(UUID bridgeId) {
        return new StringBuilder(getRouterPath(bridgeId)).append("/ports")
                .toString();
    }

    /**
     * Get ZK bridge port path.
     * 
     * @param bridgeId
     *            Bridge UUID
     * @param portId
     *            Port UUID.
     * @return /bridges/bridgeId/ports/portId
     */
    public String getBridgePortPath(UUID bridgeId, UUID portId) {
        return new StringBuilder(getBridgePortsPath(bridgeId)).append("/")
                .append(portId).toString();
    }

    /**
     * Get ZK routes path.
     * 
     * @return /routes
     */
    public String getRoutesPath() {
        return new StringBuilder(basePath).append("/routes").toString();
    }

    /**
     * Get ZK routes path. /routes/routeId
     * 
     * @param portId
     *            Port UUID
     * @param Route
     *            Route object to store.
     * @return /routes/routeId
     */
    public String getRoutePath(UUID id) {
        return new StringBuilder(getRoutesPath()).append("/").append(id)
                .toString();
    }

    /**
     * Get ZK router routes path.
     * 
     * @param routerId
     *            Router UUID
     * @return /routers/routerId/routes
     */
    public String getRouterRoutesPath(UUID routerId) {
        return new StringBuilder(getRouterPath(routerId)).append("/routes")
                .toString();
    }

    /**
     * Get ZK router routes path.
     * 
     * @param routerId
     *            Router UUID
     * @return /routers/routerId/routes/routeId
     */
    public String getRouterRoutePath(UUID routerId, UUID routeId) {
        return new StringBuilder(getRouterRoutesPath(routerId)).append("/")
                .append(routeId).toString();
    }

    /**
     * Get ZK port routes path.
     * 
     * @param portId
     *            Port UUID
     * @return /ports/portId/routes
     */
    public String getPortRoutesPath(UUID portId) {
        return new StringBuilder(getPortPath(portId)).append("/routes")
                .toString();
    }

    /**
     * Get ZK port routes path.
     * 
     * @param portId
     *            Port UUID
     * @param routeId
     *            Route ID.
     * @return /ports/portId/routes/routeId
     */
    public String getPortRoutePath(UUID portId, UUID routeId) {
        return new StringBuilder(getPortRoutesPath(portId)).append("/").append(
                routeId).toString();
    }

    /**
     * Get ZK rule chain path.
     * 
     * @return /rule_chains
     */
    public String getChainsPath() {
        return new StringBuilder(basePath).append("/chains").toString();
    }

    /**
     * Get ZK rule chain path.
     * 
     * @param id
     *            Chain UUID.
     * @return /chains/chainId
     */
    public String getChainPath(UUID id) {
        return new StringBuilder(getChainsPath()).append("/").append(id)
                .toString();
    }

    /**
     * Get ZK router rule chains path.
     * 
     * @param routerId
     *            Router UUID
     * @return /routers/routerId/chains
     */
    public String getRouterChainsPath(UUID routerId) {
        return new StringBuilder(getRouterPath(routerId)).append("/chains")
                .toString();
    }

    /**
     * Get ZK router rule chains path.
     * 
     * @param routerId
     *            Router UUID
     * @param chainId
     *            Chain UUID.
     * @return /routers/routerId/chains/chainId
     */
    public String getRouterChainPath(UUID routerId, UUID chainId) {
        return new StringBuilder(getRouterChainsPath(routerId)).append("/")
                .append(chainId).toString();
    }

    /**
     * Get ZK rule path.
     * 
     * @return /rules
     */
    public String getRulesPath() {
        return new StringBuilder(basePath).append("/rules").toString();
    }

    /**
     * Get ZK rule path.
     * 
     * @param id
     *            Rule UUID.
     * @return /rules/ruleId
     */
    public String getRulePath(UUID id) {
        return new StringBuilder(getRulesPath()).append("/").append(id)
                .toString();
    }

    /**
     * Get ZK chain rule path.
     * 
     * @param chainId
     *            Chain UUID
     * @return /chains/chainId/rules
     */
    public String getChainRulesPath(UUID chainId) {
        return new StringBuilder(getChainPath(chainId)).append("/rules")
                .toString();
    }

    /**
     * Get ZK chain rule path.
     * 
     * @param chainId
     *            Chain UUID
     * @param ruleId
     *            Rule UUID.
     * @return /chains/chainId/rules/ruleId
     */
    public String getChainRulePath(UUID chainId, UUID ruleId) {
        return new StringBuilder(getChainRulesPath(chainId)).append("/")
                .append(ruleId).toString();
    }

    /**
     * Get ZK router routing table path.
     * 
     * @param routerId
     *            Router UUID
     * @return /routers/routerId/routing_table
     */
    public String getRouterRoutingTablePath(UUID routerId) {
        return new StringBuilder(getRouterPath(routerId)).append(
                "/routing_table").toString();
    }

    /**
     * Get ZK router SNAT blocks path.
     * 
     * @param routerId
     *            Router UUID
     * @return /routers/routerId/snat_blocks
     */
    public String getRouterSnatBlocksPath(UUID routerId) {
        return new StringBuilder(getRouterPath(routerId))
                .append("/snat_blocks").toString();
    }
}
