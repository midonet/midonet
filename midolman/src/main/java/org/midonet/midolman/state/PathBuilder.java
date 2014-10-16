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
package org.midonet.midolman.state;

import java.util.UUID;

import com.google.inject.Inject;

import org.midonet.cluster.config.ZookeeperConfig;
import org.midonet.packets.IPv4Addr;

/**
 * This class was created to have all state classes share the Zk path
 * information.
 */
public class PathBuilder extends ZkPathManager {

    public static final String BRIDGE_NAMES_PATH = "bridge-names";
    public static final String CHAIN_NAMES_PATH = "chain-names";
    public static final String ROUTER_NAMES_PATH = "router-names";
    public static final String TENANTS_PATH = "tenants";
    public static final String TRACED_CONDITIONS_PATH = "trace-conditions";
    public static final String PORT_GROUP_NAMES_PATH = "port_group-names";
    public static final String LICENSES_PATH = "licenses";

    @Inject
    public PathBuilder(ZookeeperConfig config) {
        this(config.getZkRootPath());
    }

    public PathBuilder(String rootKey) {
        super(rootKey);
    }

    /**
     * Get ZK tenant port group name path.
     *
     * @param tenantId Tenant UUID
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
     * @param tenantId Tenant UUID
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
     * @param tenantId Tenant UUID
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

    private StringBuilder buildTenantBridgeNamesPath(String tenantId) {
        return buildTenantPath(tenantId).append("/").append(BRIDGE_NAMES_PATH);
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

    private StringBuilder buildTenantRouterNamesPath(String tenantId) {
        return buildTenantPath(tenantId).append("/").append(ROUTER_NAMES_PATH);
    }

    public String getTenantPath(String id) {
        return buildTenantPath(id).toString();
    }

    private StringBuilder buildTenantPath(String id) {
        return buildTenantsPath().append("/").append(id);
    }

    public String getTenantsPath() {
        return buildTenantsPath().toString();
    }

    private StringBuilder buildTenantsPath() {
        return new StringBuilder(basePath).append("/").append(TENANTS_PATH);
    }

    public String getTraceConditionsPath() {
        return buildTraceConditionsPath().toString();
    }

    private StringBuilder buildTraceConditionsPath() {
        return new StringBuilder(basePath).append("/")
                                          .append(TRACED_CONDITIONS_PATH);
    }

    public String getTraceConditionPath(UUID id) {
        return getTraceConditionsPath() + "/" + id;
    }

    public String getLicensesPath() { return buildLicensesPath().toString(); }

    private StringBuilder buildLicensesPath() {
        return new StringBuilder(basePath).append("/").append(LICENSES_PATH);
    }

    public String getLicensePath(UUID id) {
        return buildLicensePath(id).toString();
    }

    private StringBuilder buildLicensePath(UUID id) {
        return buildLicensesPath().append("/").append(id.toString());
    }

    public String getNatPath() {
        return buildNatPath().toString();
    }

    private StringBuilder buildNatPath() {
        return new StringBuilder(basePath).append("/nat");
    }

    /**
     * Get NAT blocks device path.
     *
     * @return /nat/{deviceId}
     */
    public String getNatDevicePath(UUID deviceId) {
        return buildNatDevicePath(deviceId).toString();
    }

    private StringBuilder buildNatDevicePath(UUID deviceId) {
        return buildNatPath().append("/").append(deviceId);
    }

    /**
     * Get NAT blocks device path.
     *
     * @return /nat/{deviceId}/{ip}
     */
    public String getNatIpPath(UUID deviceId, IPv4Addr ip) {
        return buildNatIpPath(deviceId, ip).toString();
    }

    private StringBuilder buildNatIpPath(UUID deviceId, IPv4Addr ip) {
        return buildNatDevicePath(deviceId).append("/").append(ip);
    }

    /**
     * Get NAT blocks individual block path.
     *
     * @return /nat/{deviceId}/{ip}/{blockIdx}
     */
    public String getNatBlockPath(UUID deviceId, IPv4Addr ip, int blockIdx) {
        return buildNatBlockPath(deviceId, ip, blockIdx).toString();
    }

    private StringBuilder buildNatBlockPath(UUID deviceId, IPv4Addr ip,
                                            int blockIdx) {
        return buildNatIpPath(deviceId, ip).append("/").append(blockIdx);
    }

    /**
     * Get a NAT block ownership path.
     *
     * @return /nat/{deviceId}/{ip}/{blockIdx}/taken
     */
    public String getNatBlockOwnershipPath(UUID deviceId, IPv4Addr ip,
                                           int blockIdx) {
        return buildNatBlockOwnershipPath(deviceId, ip, blockIdx).toString();
    }

    private StringBuilder buildNatBlockOwnershipPath(UUID deviceId, IPv4Addr ip,
                                                     int blockIdx) {
        return buildNatBlockPath(deviceId, ip, blockIdx).append("/taken");
    }
}
