/**
 *  Copyright 2005-2014 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package org.midonet.cluster.data.storage.jgroups;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.utils.ZKPaths;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.jgroups.Address;
import org.jgroups.Event;
import org.jgroups.protocols.FILE_PING;
import org.jgroups.protocols.PingData;
import org.jgroups.util.Util;
import sun.reflect.annotation.ExceptionProxy;

public abstract class AbstractZooKeeperPing extends FILE_PING {
    /* Path in ZooKeeper where broker ip:ports are published. */
    // TODO: store that in midonetconf
    public static final String ROOT_PATH = "/jgroups/registry/";

    private volatile String discoveryPath;
    private volatile String localNodePath;

    protected CuratorFramework curator;

    protected abstract CuratorFramework createCurator() throws KeeperException;

    protected CreateMode getCreateMode() throws KeeperException {
        return CreateMode.EPHEMERAL;
    }

    @Override
    public void init() throws Exception {
        curator = createCurator();
        super.init();
    }


    public Object down(Event evt) {
        switch (evt.getType()) {
            case Event.CONNECT:
            case Event.CONNECT_USE_FLUSH:
            case Event.CONNECT_WITH_STATE_TRANSFER:
            case Event.CONNECT_WITH_STATE_TRANSFER_USE_FLUSH:
                discoveryPath = ROOT_PATH + evt.getArg();
                localNodePath = discoveryPath + "/" + addressAsString(local_addr);
                _createRootDir();
                break;
        }
        return super.down(evt);
    }

    /**
     * Creates the root node in ZooKeeper (/jgroups/registry/)
     */
    @Override
    protected void createRootDir() {
        // empty on purpose to prevent dir from being created in the local file system
    }

    protected void _createRootDir() {


        try {
            if (curator.checkExists().forPath(localNodePath) == null) {
                curator.create().creatingParentsIfNeeded().withMode(getCreateMode()).forPath(localNodePath);
            }
        } catch (Exception e) {
            throw new RuntimeException(String.format("Failed to create dir %s in ZooKeeper.", localNodePath), e);
        }
    }



}
