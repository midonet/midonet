/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.guice.cluster;

import com.google.inject.Inject;
import com.google.inject.PrivateModule;
import com.google.inject.Provider;
import org.apache.curator.framework.CuratorFramework;

import org.midonet.cluster.config.ZookeeperConfig;
import org.midonet.cluster.data.storage.StorageService;
import org.midonet.cluster.data.storage.ZookeeperObjectMapper;

/**
 * Guice module to install dependencies for ZOOM-based data access.
 * TODO (ernest): this is a temporary hack to allow tests to run
 * when depending on MidostoreSetupService (which already depends on ZOOM-based
 * storage service
 */
public class MidostoreModule extends PrivateModule {

    private static class StorageServiceProvider
        implements Provider<StorageService> {
        @Inject
        ZookeeperConfig cfg;
        @Inject
        CuratorFramework curator;
        @Override public StorageService get() {
            return new ZookeeperObjectMapper(cfg.getZkRootPath() + "/zoom",
                                             curator);
        }
    }

    @Override
    protected void configure() {
        bind(StorageService.class)
            .toProvider(StorageServiceProvider.class)
            .asEagerSingleton();
        expose(StorageService.class);
    }
}
