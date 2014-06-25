/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.state.zkManagers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class to manager the license ZooKeeper data.
 */
public class LicenseZkManager
        extends AbstractZkManager<UUID, LicenseZkManager.LicenseConfig>{

    private final static Logger log = LoggerFactory
        .getLogger(LicenseZkManager.class);

    public static class LicenseConfig extends BaseConfig
                                      implements TaggableConfig {
        private UUID licenseId;
        private byte[] licenseData;

        public LicenseConfig() {
            super();
        }

        public LicenseConfig(UUID licenseId, byte[] licenseData) {
            super();
            this.licenseId = licenseId;
            this.licenseData = licenseData;
        }

        public UUID getLicenseId() {
            return licenseId;
        }

        public byte[] getLicenseData() {
            return licenseData;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object)
                return true;
            if (object == null || getClass() != object.getClass())
                return false;

            LicenseConfig config = (LicenseConfig)object;

            return licenseId == config.licenseId;
        }

        @Override
        public int hashCode() {
            return this.licenseId.hashCode();
        }

        @Override
        public String toString() {
            return "LicenseConfig{licenseId=" + licenseId +
                ", licenseDataLength=" + licenseData.length + "}";
        }
    }

    /**
     * Initializes a LicenseZkManager object with a ZooKeeper client and the
     * root path of the ZooKeeper directory.
     * @param zk The ZooKeeper data access class.
     * @param paths PathBuilder class to construct ZK paths.
     * @param serializer ZK data serialization class.
     */
    public LicenseZkManager(ZkManager zk, PathBuilder paths,
                            Serializer serializer) {
        super(zk, paths, serializer);
    }

    @Override
    protected String getConfigPath(UUID id) {
        return paths.getLicensePath(id);
    }

    @Override
    protected Class<LicenseConfig> getConfigClass() {
        return LicenseConfig.class;
    }

    /**
     * Creates a new license node in ZooKeeper, or updates the existing node
     * if the license already exists.
     * @param license The license configuration.
     */
    public void create(LicenseConfig license) throws StateAccessException {
        log.debug("Creating ZK license {} at path {}.", new Object[]{
            license,
            paths.getLicensePath(license.getLicenseId())
        });
        String path = paths.getLicensePath(license.getLicenseId());
        if (zk.exists(path)) {
            zk.update(path, license.getLicenseData());
        } else {
            zk.addPersistent(path, license.getLicenseData());
        }
    }

    /**
     * Deletes a license node from ZooKeeper.
     * @param licenseId The license identifier.
     * @throws StateAccessException
     */
    public void delete(UUID licenseId) throws StateAccessException {
        log.debug("Deleting ZK license {}", licenseId);
        zk.delete(paths.getLicensePath(licenseId));
    }

    /**
     * Gets the license blob from ZooKeeper.
     * @param licenseId The license identifier.
     * @return The license binary object.
     * @throws StateAccessException
     */
    public byte[] select(UUID licenseId) throws StateAccessException {
        log.debug("Selecting ZK license {}", licenseId);
        return zk.get(paths.getLicensePath(licenseId));
    }

    /**
     * Gets the current list of license identifiers from ZooKeeper.
     * @return The list of current license identifiers.
     * @throws StateAccessException
     */
    public Collection<UUID> list() throws StateAccessException {
        log.debug("Listing ZK licenses.");
        Collection<String> children = zk.getChildren(paths.getLicensesPath());
        List<UUID> ids = new ArrayList<>(children.size());
        for (String child : children) {
            ids.add(UUID.fromString(child));
        }
        return ids;
    }
}
