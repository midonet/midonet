/*
 * Copyright 2015 Midokura SARL
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

import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.packets.IPv4Addr;

/**
 * This class was created to have all state classes share the Zk path
 * information.
 */
public class PathBuilder extends ZkPathManager {

    public static final String TENANTS_PATH = "tenants";
    public static final String LICENSES_PATH = "licenses";

    @Inject
    public PathBuilder(MidolmanConfig config) {
        this(config.zookeeper().rootKey());
    }

    public PathBuilder(String rootKey) {
        super(rootKey);
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

    public String getLicensesPath() { return buildLicensesPath().toString(); }

    private StringBuilder buildLicensesPath() {
        return new StringBuilder(basePath).append("/").append(LICENSES_PATH);
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
