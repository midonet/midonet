/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster.services;

import com.google.common.util.concurrent.AbstractService;
import com.midokura.midolman.Setup;
import com.midokura.midolman.config.ZookeeperConfig;
import com.midokura.midolman.state.Directory;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;


/**
 * // TODO: mtoader ! Please explain yourself.
 */
public class MidostoreSetupService extends AbstractService {

    private static final Logger log = LoggerFactory
            .getLogger(MidostoreSetupService.class);
    @Inject
    Directory directory;

    @Inject
    ZookeeperConfig config;

    @Override
    protected void doStart() {
        try {
            String rootKey = config.getMidolmanRootKey();

            String currentPath = "";
            for (String part : rootKey.split("/+")) {
                if (part.trim().isEmpty())
                    continue;

                currentPath += "/" + part;
                try {
					if (!directory.has(currentPath)) {
						log.debug("Adding " + currentPath);
						directory.add(currentPath, null, CreateMode.PERSISTENT);
					}
                } catch (KeeperException.NodeExistsException ex) {
                    // Don't exit even if the node exists.
                    log.warn("doStart: {} already exists.", currentPath);
                }
            }
            Setup.createZkDirectoryStructure(directory, rootKey);
            notifyStarted();
        } catch (Exception e) {
            this.notifyFailed(e);
        }
    }

    @Override
    protected void doStop() {
        notifyStopped();
    }
}
