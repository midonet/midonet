/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.zookeeper;

import com.google.inject.Inject;
import com.midokura.midolman.guice.zookeeper.DirectoryProvider;
import com.midokura.midolman.state.Directory;

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
            return new StaticMockDirectory().getDirectoryInstance();
        } else {
            return super.get();
        }

    }

}
