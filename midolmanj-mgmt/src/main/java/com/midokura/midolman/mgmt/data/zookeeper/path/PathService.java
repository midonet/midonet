/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.path;

import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ZooKeeper path service
 */
public class PathService {

    private final static Logger log = LoggerFactory
            .getLogger(PathService.class);
    private final PathBuilder pathBuilder;

    /**
     * Constructor
     *
     * @param pathBuilder
     *            ZooKeeper path builder service.
     */
    public PathService(PathBuilder pathBuilder) {
        this.pathBuilder = pathBuilder;
    }

    /**
     * Get a sorted set of ZK paths required for initialization.
     *
     * @return Set of ZK paths.
     */
    public Set<String> getInitialPaths() {
        log.debug("PathService.getInitialPaths entered.");

        List<String> paths = PathHelper.getSubPaths(pathBuilder.getBasePath());

        // Convert to a sorted set to remove duplicates
        SortedSet<String> pathSet = new TreeSet<String>();
        pathSet.addAll(paths);
        pathSet.add(pathBuilder.getRoutersPath());
        pathSet.add(pathBuilder.getBridgesPath());
        pathSet.add(pathBuilder.getPortsPath());
        pathSet.add(pathBuilder.getChainsPath());
        pathSet.add(pathBuilder.getFiltersPath());
        pathSet.add(pathBuilder.getGrePath());
        pathSet.add(pathBuilder.getPortSetsPath());
        pathSet.add(pathBuilder.getRulesPath());
        pathSet.add(pathBuilder.getRoutesPath());
        pathSet.add(pathBuilder.getBgpPath());
        pathSet.add(pathBuilder.getAdRoutesPath());
        pathSet.add(pathBuilder.getVRNPortLocationsPath());
        pathSet.add(pathBuilder.getVpnPath());
        pathSet.add(pathBuilder.getAgentPath());
        pathSet.add(pathBuilder.getAgentPortPath());
        pathSet.add(pathBuilder.getAgentVpnPath());
        pathSet.add(pathBuilder.getHostsPath());
        pathSet.add(pathBuilder.getPortGroupsPath());
        pathSet.add(pathBuilder.getTenantsPath());

        log.debug("PathService.getInitialPaths exiting: pathSet count={}",
                pathSet.size());
        return pathSet;
    }

}
