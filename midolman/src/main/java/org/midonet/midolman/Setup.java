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

package org.midonet.midolman;

import java.util.ArrayList;
import java.util.List;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.version.DataWriteVersion;

import static org.midonet.midolman.state.zkManagers.VtepZkManager.MIN_VNI;

public class Setup {

    static final Logger log = LoggerFactory.getLogger(Setup.class);

    private static List<String> getTopLevelPaths(PathBuilder pathMgr) {
        List<String> paths = new ArrayList<>();
        paths.add(pathMgr.getAdRoutesPath());
        paths.add(pathMgr.getBgpPath());
        paths.add(pathMgr.getBridgesPath());
        paths.add(pathMgr.getVlanBridgesPath());
        paths.add(pathMgr.getChainsPath());
        paths.add(pathMgr.getFiltersPath());
        paths.add(pathMgr.getRulesPath());
        paths.add(pathMgr.getTunnelPath());
        paths.add(pathMgr.getTunnelZonesPath());
        paths.add(pathMgr.getPortsPath());
        paths.add(pathMgr.getPortSetsPath());
        paths.add(pathMgr.getRoutersPath());
        paths.add(pathMgr.getRoutesPath());
        paths.add(pathMgr.getAgentPath());
        paths.add(pathMgr.getAgentPortPath());
        paths.add(pathMgr.getPortGroupsPath());
        paths.add(pathMgr.getIpAddrGroupsPath());
        paths.add(pathMgr.getHostsPath());
        paths.add(pathMgr.getTenantsPath());
        paths.add(pathMgr.getVersionsPath());
        paths.add(pathMgr.getVersionPath(DataWriteVersion.CURRENT));
        paths.add(pathMgr.getSystemStatePath());
        paths.add(pathMgr.getHealthMonitorsPath());
        paths.add(pathMgr.getLoadBalancersPath());
        paths.add(pathMgr.getPoolHealthMonitorMappingsPath());
        paths.add(pathMgr.getPoolMembersPath());
        paths.add(pathMgr.getPoolsPath());
        paths.add(pathMgr.getVipsPath());
        paths.add(pathMgr.getHealthMonitorLeaderDirPath());
        paths.add(pathMgr.getVtepsPath());
        paths.add(pathMgr.getVxLanPortIdsPath());
        paths.add(pathMgr.getLocksPath());
        paths.add(pathMgr.getLicensesPath());
        paths.add(pathMgr.getNatPath());

        // Neutron paths
        paths.add(pathMgr.getNeutronPath());
        paths.add(pathMgr.getNeutronNetworksPath());
        paths.add(pathMgr.getNeutronSubnetsPath());
        paths.add(pathMgr.getNeutronPortsPath());
        paths.add(pathMgr.getNeutronRoutersPath());
        paths.add(pathMgr.getNeutronFloatingIpsPath());
        paths.add(pathMgr.getNeutronSecurityGroupsPath());
        paths.add(pathMgr.getNeutronSecurityGroupRulesPath());
        // Neutron LoadBalancer paths
        paths.add(pathMgr.getNeutronLoadBalancerPath());
        paths.add(pathMgr.getNeutronPoolsPath());
        paths.add(pathMgr.getNeutronVipsPath());
        paths.add(pathMgr.getNeutronMembersPath());
        paths.add(pathMgr.getNeutronHealthMonitorsPath());

        return paths;
    }

    public static void ensureZkDirectoryStructureExists(Directory rootDir,
                                                        String basePath)
        throws KeeperException, InterruptedException
    {
        ensureBasePathExists(rootDir, basePath);
        PathBuilder pathMgr = new PathBuilder(basePath);
        for (String path : Setup.getTopLevelPaths(pathMgr)) {
            rootDir.ensureHas(path, null);
        }
        rootDir.ensureHas(pathMgr.getVniCounterPath(),
                          Integer.toString(MIN_VNI).getBytes());
        rootDir.ensureHas(pathMgr.getWriteVersionPath(),
                          DataWriteVersion.CURRENT.getBytes());
    }

    public static void ensureBasePathExists(Directory rootDir,
                                            String basePath)
            throws KeeperException, InterruptedException {
        String currentPath = "";
        for (String part : basePath.split("/+")) {
            if (part.trim().isEmpty())
                continue;

            currentPath += "/" + part;
            try {
                if (!rootDir.has(currentPath)) {
                    log.debug("Adding " + currentPath);
                    rootDir.add(currentPath, null, CreateMode.PERSISTENT);
                }
            } catch (KeeperException.NodeExistsException ex) {
                // Don't exit even if the node exists.
                log.warn("doStart: {} already exists.", currentPath);
            }
        }
    }
}
