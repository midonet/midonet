/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midostore.module;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.host.state.HostZkManager;
import com.midokura.midolman.config.MidolmanConfig;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.ZkManager;
import com.midokura.midolman.state.zkManagers.BgpZkManager;
import com.midokura.midostore.LocalMidostoreClient;
import com.midokura.midostore.MidostoreClient;
import com.midokura.midostore.services.MidostoreSetupService;

public class MidoStoreModule extends AbstractModule {

    private static final Logger log = LoggerFactory
        .getLogger(MidoStoreModule.class);

    @Override
    protected void configure() {

        bind(MidostoreClient.class)
            .to(LocalMidostoreClient.class)
            .asEagerSingleton();

        bindManagers();

        bind(MidostoreSetupService.class).in(Singleton.class);
    }

    protected void bindManagers() {
        List<Class<? extends ZkManager>> managers = new ArrayList<Class<? extends ZkManager>>();

        managers.add(HostZkManager.class);
        managers.add(BgpZkManager.class);
//        managers.add(HostZkManager.class);
//        managers.add(HostZkManager.class);

        for (Class<? extends ZkManager> managerClass : managers) {
            bind(managerClass)
                .toProvider(new ZkManagerProvider(managerClass))
                .asEagerSingleton();
        }
    }

    private static class ZkManagerProvider<T extends ZkManager>
        implements Provider<T> {

        @Inject
        Directory directory;

        @Inject
        MidolmanConfig config;

        Class<T> managerClass;

        protected ZkManagerProvider(Class<T> managerClass) {
            this.managerClass = managerClass;
        }

        @Override
        public T get() {
            try {
                Constructor<T> constructor =
                    managerClass.getConstructor(Directory.class, String.class);

                return
                    constructor.newInstance(
                        directory,
                        config.getMidolmanRootKey());

            } catch (Exception e) {
                throw new RuntimeException("Could not create zkManager of class: " + managerClass, e);
            }
        }
    }
}
