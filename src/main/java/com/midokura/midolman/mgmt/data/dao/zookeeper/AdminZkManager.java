/*
 * @(#)AdminZkManager        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.AdminDao;
import com.midokura.midolman.mgmt.utils.CollectionUtils;
import com.midokura.midolman.mgmt.utils.StringUtils;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.StatePathExistsException;

public class AdminZkManager extends ZkMgmtManager implements AdminDao {

    private final static Logger log = LoggerFactory
            .getLogger(AdminZkManager.class);

    public AdminZkManager(Directory zk, String basePath, String mgmtBasePath) {
        super(zk, basePath, mgmtBasePath);
    }

    private List<String> excecuteCreate(List<String> paths)
            throws StateAccessException {
        List<String> added = new ArrayList<String>();
        for (String path : paths) {
            try {
                log.debug("Preparing to create: " + path);
                addPersistent(path, null);
                added.add(path);
            } catch (StatePathExistsException e) {
                // We can ignore this.
            }
        }
        return added;
    }

    @Override
    public void initialize() throws StateAccessException {
        List<String> rootPaths = StringUtils.splitAndAppend(
                mgmtPathManager.getBasePath(), "/");
        List<String> mgmtRootPaths = StringUtils.splitAndAppend(
                pathManager.getBasePath(), "/");

        @SuppressWarnings("unchecked")
        List<String> paths = CollectionUtils.uniquifyAndSort(rootPaths,
                mgmtRootPaths);
        paths.add(mgmtPathManager.getTenantsPath());
        paths.add(mgmtPathManager.getRoutersPath());
        paths.add(mgmtPathManager.getBridgesPath());
        paths.add(mgmtPathManager.getPortsPath());
        paths.add(mgmtPathManager.getChainsPath());
        paths.add(mgmtPathManager.getVifsPath());
        paths.add(pathManager.getRoutersPath());
        paths.add(pathManager.getBridgesPath());
        paths.add(pathManager.getPortsPath());
        paths.add(pathManager.getChainsPath());
        paths.add(pathManager.getGrePath());
        paths.add(pathManager.getRulesPath());
        paths.add(pathManager.getRoutesPath());
        paths.add(pathManager.getBgpPath());
        paths.add(pathManager.getAdRoutesPath());
        paths.add(pathManager.getVRNPortLocationsPath());
        paths.add(pathManager.getVpnPath());
        excecuteCreate(paths);
    }
}
