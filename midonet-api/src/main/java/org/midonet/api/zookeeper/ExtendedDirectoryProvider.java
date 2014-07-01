/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.zookeeper;

import com.google.inject.Inject;
import org.midonet.midolman.guice.zookeeper.DirectoryProvider;
import org.midonet.midolman.state.Directory;

/**
 * Extended Directory provider
 */
public class ExtendedDirectoryProvider extends DirectoryProvider {

    private final ExtendedZookeeperConfig config;

    @Inject
    public ExtendedDirectoryProvider(ExtendedZookeeperConfig config) {
        this.config = config;
    }

    @Override
    public Directory get() {

        if(config.getUseMock()) {
            return StaticMockDirectory.getDirectoryInstance();
        } else {
            return super.get();
        }

    }

}
