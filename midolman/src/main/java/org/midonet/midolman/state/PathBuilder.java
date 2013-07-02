/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.midolman.state;

import java.util.UUID;
import com.google.inject.Inject;
import org.midonet.midolman.config.ZookeeperConfig;

/**
 * This class was created to have all state classes share the Zk path
 * information.
 */
public class PathBuilder extends ZkPathManager {

    public static final String BRIDGE_NAMES_PATH = "bridge-names";
    public static final String VLAN_BRIDGE_NAMES_PATH = "vlan-bridge-names";
    public static final String CHAIN_NAMES_PATH = "chain-names";
    public static final String ROUTER_NAMES_PATH = "router-names";
    public static final String TENANTS_PATH = "tenants";
    public static final String TRACED_CONDITIONS_PATH = "traced-conditions";
    public static final String PORT_GROUP_NAMES_PATH = "port_group-names";

    @Inject
    public PathBuilder(ZookeeperConfig config) {
        this(config.getMidolmanRootKey());
    }

    public PathBuilder(String rootKey) {
        super(rootKey);
    }

    /**
     * Get ZK tenant port group name path.
     *
     * @param tenantId
     *            Tenant UUID
     * @return /tenants/tenantId/port_group-names/name
     */
    public String getTenantPortGroupNamePath(String tenantId, String name) {
        return buildTenantPortGroupNamePath(tenantId, name).toString();
    }

    private StringBuilder buildTenantPortGroupNamePath(String tenantId,
                                                       String name) {
        return buildTenantPortGroupNamesPath(tenantId).append("/").append(name);
    }

    /**
     * Get ZK tenant port group names path.
     *
     * @param tenantId
     *            Tenant UUID
     * @return /tenant/tenantId/port_group-names
     */
    public String getTenantPortGroupNamesPath(String tenantId) {
        return buildTenantPortGroupNamesPath(tenantId).toString();
    }

    private StringBuilder buildTenantPortGroupNamesPath(String tenantId) {
        return buildTenantPath(tenantId).append("/").append(
            PORT_GROUP_NAMES_PATH);
    }

    /**
     * Get ZK tenant chain name path.
     *
     * @param tenantId
     *            Tenant UUID
     * @return /tenants/tenantId/chain-names/name
     */
    public String getTenantChainNamePath(String tenantId, String name) {
        return buildTenantChainNamePath(tenantId, name).toString();
    }

    private StringBuilder buildTenantChainNamePath(String tenantId,
                                                   String name) {
        return buildTenantChainNamesPath(tenantId).append("/").append(name);
    }

    /**
     * Get ZK tenant chain-names path.
     *
     * @param tenantId
     *            Tenant UUID
     * @return /tenant/tenantId/chain-names
     */
    public String getTenantChainNamesPath(String tenantId) {
        return buildTenantChainNamesPath(tenantId).toString();
    }

    public StringBuilder buildTenantChainNamesPath(String tenantId) {
        return buildTenantPath(tenantId).append("/").append(CHAIN_NAMES_PATH);
    }

    /**
     * Get ZK tenant bridge name path.
     *
     * @return /tenants/tenantId/bridge-names/name
     */
    public String getTenantBridgeNamePath(String tenantId, String name) {
        return buildTenantBridgeNamePath(tenantId, name).toString();
    }

    public StringBuilder buildTenantBridgeNamePath(String tenantId,
                                                   String name) {
        return buildTenantBridgeNamesPath(tenantId).append("/").append(name);
    }

    /**
     * Get ZK tenant bridge names path.
     *
     * @return /tenants/tenantId/bridge-names
     */
    public String getTenantBridgeNamesPath(String tenantId) {
        return buildTenantBridgeNamesPath(tenantId).toString();
    }


    private StringBuilder buildTenantBridgeNamesPath(String tenantId) {
        return buildTenantPath(tenantId).append("/").append(BRIDGE_NAMES_PATH);
    }

    public String getTenantVlanBridgeNamePath(String tenantId, String name) {
        return buildTenantVlanBridgeNamePath(tenantId, name).toString();
    }

    public StringBuilder buildTenantVlanBridgeNamePath(String tenantId,
                                                       String name) {
        return buildTenantVlanBridgeNamesPath(tenantId).append("/").append(name);
    }

    public String getTenantVlanBridgeNamesPath(String tenantId) {
        return buildTenantVlanBridgeNamesPath(tenantId).toString();
    }

    private StringBuilder buildTenantVlanBridgeNamesPath(String tenantId) {
        return buildTenantPath(tenantId).append("/").append(VLAN_BRIDGE_NAMES_PATH);
    }

    /**
     * Get ZK tenant router name path.
     *
     * @return /tenants/tenantId/router-names/name
     */
    public String getTenantRouterNamePath(String tenantId, String name) {
        return buildTenantRouterNamePath(tenantId, name).toString();
    }

    private StringBuilder buildTenantRouterNamePath(String tenantId,
                                                    String name) {
        return buildTenantRouterNamesPath(tenantId).append("/").append(name);
    }

    /**
     * Get ZK tenant router names path.
     *
     * @return /tenants/tenantId/router-names
     */
    public String getTenantRouterNamesPath(String tenantId) {
        return buildTenantRouterNamesPath(tenantId).toString();
    }

    private StringBuilder buildTenantRouterNamesPath(String tenantId) {
        return buildTenantPath(tenantId).append("/").append(ROUTER_NAMES_PATH);
    }

    /**
     * Get ZK tenant path.
     *
     * @param id
     *            Tenant ID
     * @return /tenants/tenantId
     */
    public String getTenantPath(String id) {
        return buildTenantPath(id).toString();
    }

    private StringBuilder buildTenantPath(String id) {
        return buildTenantsPath().append("/").append(id);
    }

    /**
     * Get ZK tenant path.
     *
     * @return /tenants
     */
    public String getTenantsPath() {
        return buildTenantsPath().toString();
    }

    private StringBuilder buildTenantsPath() {
        return new StringBuilder(basePath).append("/").append(TENANTS_PATH);
    }

    public String getTracedConditionsPath() {
        return buildTracedConditionsPath().toString();
    }

    private StringBuilder buildTracedConditionsPath() {
        return new StringBuilder(basePath).append("/")
                                          .append(TRACED_CONDITIONS_PATH);
    }

    /**
    * Get ZK trace condition path
    *
    * @param id Trace condition id
    * @return /trace-conditions/id
    */
    public String getTracedConditionPath(UUID id) {
        StringBuilder tcBuilder =
            new StringBuilder(getTracedConditionsPath()).append("/").append(id);
        return tcBuilder.toString();
    }
}
