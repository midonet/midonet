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
package org.midonet.cluster.services;

import com.google.common.util.concurrent.AbstractService;
import org.apache.curator.framework.CuratorFramework;
import org.midonet.cluster.config.ZookeeperConfig;
import org.midonet.cluster.data.storage.StorageService;
import org.midonet.midolman.Setup;
import org.midonet.midolman.SystemDataProvider;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.version.DataWriteVersion;

import javax.inject.Inject;


/**
 * The MidostoreSetupService is in charge of ensuring that the topology storage
 * is using the expected version. It expects to have a fully operative
 * connection to ZK, or will block until it can connect.
 */
public class MidostoreSetupService extends AbstractService {

    @Inject
    protected Directory directory;

    @Inject
    protected ZookeeperConfig config;

    @Inject
    protected SystemDataProvider systemDataProvider;

    @Inject
    protected CuratorFramework curator;

    @Inject
    protected StorageService store;

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
