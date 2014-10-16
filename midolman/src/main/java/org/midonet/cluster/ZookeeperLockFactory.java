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
package org.midonet.cluster;

import com.google.common.base.Preconditions;
import com.google.inject.Inject;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreMutex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.state.PathBuilder;

/**
 * Factory class to construct Curator lock objects
 */
public class ZookeeperLockFactory {

    private static final Logger logger =
        LoggerFactory.getLogger(ZookeeperLockFactory.class);

    private final CuratorFramework client;
    private final PathBuilder paths;

    @Inject
    public ZookeeperLockFactory(CuratorFramework client, PathBuilder paths) {
        this.client = client;
        this.paths = paths;
    }

    /**
     * Construct a new InterProcessSemaphoreMutex object, which is a
     * non-reentrant shared lock
     *
     * @param name Name of the lock to create.  A lock is global so if you
     *             call this method twice with the same name, the returned
     *             object is referring to the same lock (Zookeeper path).
     * @return InterProcessSemaphoreMutex shared lock object
     */
    public InterProcessSemaphoreMutex createShared(String name) {

        // TODO: check if the name is a valid ZK path name
        Preconditions.checkNotNull(name);
        logger.debug("Constructing a lock with name {}", name);

        String path = paths.getLockPath(name);
        return new InterProcessSemaphoreMutex(client, path);
    }

}
