package com.midokura.midolman.mgmt.data.dao;

import java.util.ArrayList;
import java.util.List;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;

import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;

public class AdminZkManager extends ZkMgmtManager {

    public AdminZkManager(Directory zk, String basePath, String mgmtBasePath) {
        super(zk, basePath, mgmtBasePath);
    }

    private List<Op> preparePathsCreate(List<String> paths) {
        List<Op> ops = new ArrayList<Op>();
        for (String path : paths) {
            ops.add(Op.create(path, null, Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
        }
        return ops;
    }

    public void initialize() throws StateAccessException {
        List<String> paths = new ArrayList<String>();
        paths.add(mgmtPathManager.getBasePath());
        paths.add(mgmtPathManager.getTenantsPath());
        paths.add(mgmtPathManager.getRoutersPath());
        paths.add(mgmtPathManager.getBridgesPath());
        paths.add(mgmtPathManager.getPortsPath());
        paths.add(mgmtPathManager.getChainsPath());
        paths.add(mgmtPathManager.getVifsPath());
        paths.add(pathManager.getBasePath());
        paths.add(pathManager.getRoutersPath());
        paths.add(pathManager.getBridgesPath());
        paths.add(pathManager.getPortsPath());
        paths.add(pathManager.getChainsPath());
        paths.add(pathManager.getGrePath());
        paths.add(pathManager.getRulesPath());
        paths.add(pathManager.getRoutesPath());
        paths.add(pathManager.getBgpPath());
        paths.add(pathManager.getAdRoutesPath());
        multi(preparePathsCreate(paths));
    }
}
