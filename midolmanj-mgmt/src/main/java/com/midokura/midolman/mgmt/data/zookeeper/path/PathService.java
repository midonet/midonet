/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.path;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.state.ZkPathManager;

/**
 * ZooKeeper path service
 */
public class PathService {

    private final static Logger log = LoggerFactory
            .getLogger(PathService.class);
    private final ZkPathManager pathManager;
    private final PathBuilder pathBuilder;

    /**
     * Constructor
     *
     * @param pathManager
     *            ZooKeeper path manager object.
     * @param pathBuilder
     *            ZooKeeper path builder service.
     */
    public PathService(ZkPathManager pathManager, PathBuilder pathBuilder) {
        this.pathManager = pathManager;
        this.pathBuilder = pathBuilder;
    }

    /**
     * Get a sorted set of ZK paths required for initialization.
     *
     * @return Set of ZK paths.
     */
    public Set<String> getInitialPaths() {
        log.debug("PathService.getInitialPaths entered.");

        List<String> paths = PathHelper.getSubPaths(pathManager.getBasePath());
        List<String> mgmtPaths = PathHelper.getSubPaths(pathBuilder
                .getBasePath());

        // Convert to a sorted set to remove duplicates
        Set<String> pathSet = new TreeSet<String>();
        pathSet.addAll(paths);
        pathSet.addAll(mgmtPaths);
        pathSet.add(pathManager.getRoutersPath());
        pathSet.add(pathManager.getBridgesPath());
        pathSet.add(pathManager.getPortsPath());
        pathSet.add(pathManager.getChainsPath());
        pathSet.add(pathManager.getFiltersPath());
        pathSet.add(pathManager.getGrePath());
        pathSet.add(pathManager.getPortSetsPath());
        pathSet.add(pathManager.getRulesPath());
        pathSet.add(pathManager.getRoutesPath());
        pathSet.add(pathManager.getBgpPath());
        pathSet.add(pathManager.getAdRoutesPath());
        pathSet.add(pathManager.getVRNPortLocationsPath());
        pathSet.add(pathManager.getVpnPath());
        pathSet.add(pathManager.getAgentPath());
        pathSet.add(pathManager.getAgentPortPath());
        pathSet.add(pathManager.getAgentVpnPath());
        pathSet.add(pathManager.getHostsPath());
        pathSet.add(pathBuilder.getTenantsPath());
        pathSet.add(pathBuilder.getRoutersPath());
        pathSet.add(pathBuilder.getBridgesPath());
        pathSet.add(pathBuilder.getPortsPath());
        pathSet.add(pathBuilder.getChainsPath());
        pathSet.add(pathBuilder.getPortGroupsPath());

        log.debug("PathService.getInitialPaths exiting: pathSet count={}",
                pathSet.size());
        return pathSet;
    }

}
