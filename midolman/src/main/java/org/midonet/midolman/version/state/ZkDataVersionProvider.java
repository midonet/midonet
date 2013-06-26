/*
 * Copyright 2013 Midokura PTE
 */
package org.midonet.midolman.version.state;

import com.google.inject.Inject;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.version.DataVersionProvider;
import org.midonet.midolman.version.guice.VerCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;

/**
 * This class is the zookeeper data access class for the version info.
 */
public class ZkDataVersionProvider implements DataVersionProvider {

    private final static Logger log =
        LoggerFactory.getLogger(ZkDataVersionProvider.class);

    private ZkManager zk;
    private PathBuilder paths;
    private final Comparator comparator;

    @Inject
    public ZkDataVersionProvider(ZkManager zk, PathBuilder paths,
                                 @VerCheck Comparator comparator) {
        this.zk = zk;
        this.paths = paths;
        this.comparator = comparator;
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

    /**
     * Get the current write version.
     *
     * @return  The curretn write version
     * @throws StateAccessException
     */
    @Override
    public String getWriteVersion() throws StateAccessException {
        log.debug("Entered ZkDataVersionProvider.getWriteVersion");
        final String lockPath = paths.getWriteVersionLockPath();
        /*
         * The version needs to be under lock because the upgrade
         * coordinator may be in the process of updating the version
         * info.
         */
        String version = null;

        zk.lock(lockPath);
        byte[] data = zk.get(paths.getWriteVersionPath());
        if (data != null) {
            version = new String(data);
        }
        zk.unlock(lockPath);

        log.debug("Exiting ZkDataVersionProvider.getWriteVersion. " +
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
}
