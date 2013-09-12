/*
 * Copyright 2013 Midokura PTE
 */
package org.midonet.midolman.state;

import com.google.inject.Inject;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.midonet.cluster.data.SystemState;
import org.midonet.midolman.version.guice.VerCheck;
import org.midonet.midolman.SystemDataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * This class is the zookeeper data access class for system data info.
 */
public class ZkSystemDataProvider implements SystemDataProvider {

    private final static Logger log =
        LoggerFactory.getLogger(ZkSystemDataProvider.class);

    private ZkManager zk;
    private PathBuilder paths;
    private final Comparator comparator;

    @Inject
    public ZkSystemDataProvider(ZkManager zk, PathBuilder paths,
                                 @VerCheck Comparator comparator) {
        this.zk = zk;
        this.paths = paths;
        this.comparator = comparator;
    }

    @Override
    public boolean systemUpgradeStateExists() throws StateAccessException {
        String systemStateUpgradePath = paths.getSystemStateUpgradePath();
        return zk.exists(systemStateUpgradePath);
    }

    @Override
    public boolean writeVersionExists() throws StateAccessException {
        String writeVersionPath = paths.getWriteVersionPath();
        if (zk.exists(writeVersionPath)) {
            return (zk.get(writeVersionPath) != null);
        } else {
            return false;
        }
    }

    @Override
    public void setWriteVersion(String version) throws StateAccessException {
        String writeVersionPath = paths.getWriteVersionPath();
        zk.update(writeVersionPath, version.getBytes());
    }

    @Override
    public void setOperationState(String state) throws StateAccessException {
        if (state.equals(SystemState.State.UPGRADE.toString())) {
            try {
                zk.add(paths.getSystemStateUpgradePath(), null, CreateMode.PERSISTENT);
            } catch (StatePathExistsException e) {
                // Do nothing. We won't treat this as an error.
            }
        } else if (state.equals(SystemState.State.ACTIVE.toString())) {
            try {
                zk.delete(paths.getSystemStateUpgradePath());
            } catch (NoStatePathException e) {
                // Do nothing. We won't treat this as an error.
            }
        }
    }

    @Override
    public void setConfigState(String state) throws StateAccessException {
        if (state.equals(SystemState.Availability.READONLY.toString())) {
            try {
                zk.add(paths.getConfigReadOnlyPath(), null, CreateMode.PERSISTENT);
            } catch (StatePathExistsException e) {
                // Do nothing. We won't treat this as an error.
            }
        } else if (state.equals((SystemState.Availability.READWRITE.toString()))) {
            try {
                zk.delete(paths.getConfigReadOnlyPath());
            } catch (NoStatePathException e) {
                // Do nothing. We won't treat this as an error.
            }
        }
    }

    @Override
    public boolean configReadOnly()
        throws StateAccessException {
        String configReadOnlyPath = paths.getConfigReadOnlyPath();
        return zk.exists(configReadOnlyPath);
    }

    /**
     * Get the current write version in a plain string.
     *
     * @return  The current write version
     * @throws StateAccessException
     */
    @Override
    public String getWriteVersion() throws StateAccessException {
        log.trace("Entered ZkSystemDataProvider.getWriteVersion");
        String version = null;
        byte[] data = zk.get(paths.getWriteVersionPath());
        if (data != null) {
            version = new String(data);
        }
        log.trace("Exiting ZkSystemDataProvider.getWriteVersion. " +
                "Version={}", version);
        return version;
    }

    /**
     * Checks if the given version is before the write version.
     *
     * @param version Version to check
     * @return True if the version is before the write version
     * @throws StateAccessException
     */
    @Override
    public boolean isBeforeWriteVersion(String version)
            throws StateAccessException {
        return (comparator.compare(version, this.getWriteVersion()) < 0);
    }

    public List<String> getVersionsInDeployment()
            throws StateAccessException {
        List<String> versionList = new ArrayList<String>();
        Set<String> versionSet = zk.getChildren(paths.getVersionDirPath());
        for (String version : versionSet) {
            versionList.add(version);
        }
        return versionList;
    }

    public List<String> getHostsWithVersion(String version)
            throws StateAccessException {
        List<String> hosts = new ArrayList<String>();
        Set<String> hostSet = zk.getChildren(paths.getVersionPath(version));
        for (String host : hostSet) {
            hosts.add(host);
        }
        return hosts;
    }
}
