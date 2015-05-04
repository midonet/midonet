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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.google.inject.Inject;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.data.SystemState;
import org.midonet.midolman.SystemDataProvider;
import org.midonet.midolman.cluster.serialization.VerCheck;

/**
 * This class is the zookeeper data access class for system data info.
 */
public class ZkSystemDataProvider implements SystemDataProvider {

    private final static Logger log =
        LoggerFactory.getLogger(ZkSystemDataProvider.class);

    private ZkManager zk;
    private PathBuilder paths;
    private final Comparator<String> comparator;
    private AtomicReference<String> cachedWriteVersion
        = new AtomicReference<String>(null);

    private Runnable writeVersionWatcher = new WriteVersionWatcher();

    @Inject
    public ZkSystemDataProvider(ZkManager zk, PathBuilder paths,
                                 @VerCheck Comparator<String> comparator) {
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
        cachedWriteVersion.set(version);
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
        String version = cachedWriteVersion.get();
        if (version == null) {
            byte[] data = zk.get(paths.getWriteVersionPath(),
                                 writeVersionWatcher);
            if (data != null) {
                version = new String(data);
                cachedWriteVersion.set(version);
            }
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
        Set<String> versionSet = zk.getChildren(paths.getVersionsPath());
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

    class WriteVersionWatcher extends Directory.DefaultTypedWatcher
        implements Runnable, DirectoryCallback<byte[]> {
        final int DEFAULT_RETRIES = 10;
        int retries = DEFAULT_RETRIES;

        public void onSuccess(byte[] data) {
            cachedWriteVersion.set(new String(data));
        }

        public void onTimeout() {
            log.error("Timeout reading from zookeeper, trying again");
            readWriteVersion();
        }

        public void onError(KeeperException e) {
            log.error("Error reading from zookeeper, trying again", e);
            readWriteVersion();
        }

        public void readWriteVersion() {
            if (--retries > 0) {
                zk.asyncGet(paths.getWriteVersionPath(),
                            this, this);
            } else {
                cachedWriteVersion.set(null);
            }
        }

        public void run() {
            int retries = DEFAULT_RETRIES;
            readWriteVersion();
        }
    }

}
