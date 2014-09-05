/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2013 Midokura PTE LTD
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.services;

import javax.inject.Inject;

import com.google.common.util.concurrent.AbstractService;

import org.apache.curator.framework.CuratorFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.config.ZookeeperConfig;
import org.midonet.midolman.Setup;
import org.midonet.midolman.SystemDataProvider;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.version.DataWriteVersion;


/**
 * The MidostoreSetupService is in charge of ensuring that the topology storage
 * is using the expected version. It expects to have a fully operative
 * connection to ZK, or will block until it can connect.
 */
public class MidostoreSetupService extends AbstractService {

    private static final Logger log =
        LoggerFactory.getLogger(MidostoreSetupService.class);

    @Inject
    protected Directory directory;

    @Inject
    protected ZookeeperConfig config;

    @Inject
    protected SystemDataProvider systemDataProvider;

    @Inject
    protected CuratorFramework curator;

    @Override
    protected void doStart() {
        try {
            final String rootKey = config.getZkRootPath();
            Setup.ensureZkDirectoryStructureExists(directory, rootKey);
            verifyVersion();
            verifySystemState();
            if (config.getCuratorEnabled()) {
                curator.start();
            }
            notifyStarted();
        } catch (Exception e) {
            this.notifyFailed(e);
        }
    }

    public void verifySystemState() throws StateAccessException {
        if (systemDataProvider.systemUpgradeStateExists()) {
            throw new RuntimeException("Midolman is locked for "
                        + "upgrade. Please restart when upgrade is"
                        + " complete.");
        }
    }

    public void verifyVersion() throws StateAccessException {

        if (!systemDataProvider.writeVersionExists()) {
            systemDataProvider.setWriteVersion(DataWriteVersion.CURRENT);
        }

        if (systemDataProvider.isBeforeWriteVersion(DataWriteVersion.CURRENT)) {
            throw new RuntimeException("Midolmans version ("
                    + DataWriteVersion.CURRENT
                    + ") is lower than the write version ("
                    + systemDataProvider.getWriteVersion() + ").");
        }
    }

    @Override
    protected void doStop() {
        curator.close(); // will work even if not started
        notifyStopped();
    }
}
