/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.state.zkManagers;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.DirectoryCallback;
import com.midokura.midolman.state.DirectoryCallbacks;
import com.midokura.midolman.state.ZkManager;
import com.midokura.util.functors.CollectionFunctors;
import com.midokura.util.functors.Functor;

public class PortSetZkManager extends ZkManager {

    private static final Logger log = LoggerFactory
        .getLogger(PortSetZkManager.class);

    public PortSetZkManager(Directory zk, String basePath) {
        super(zk, basePath);
    }

    public void asyncGetPortSet(UUID portSetId,
                                final DirectoryCallback<Set<UUID>> portSetContentsCallback,
                                Directory.TypedWatcher watcher) {

        String portSetPath = pathManager.getPortSetPath(portSetId);

        zk.asyncGetChildren(
            portSetPath,
            DirectoryCallbacks.transform(
                portSetContentsCallback,
                new Functor<Set<String>, Set<UUID>>() {
                    @Override
                    public Set<UUID> apply(Set<String> arg0) {
                        return CollectionFunctors.map(arg0, strToUUIDMapper, new HashSet<UUID>());
                    }
                }
            ), watcher);
    }
}
