/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midostore.module;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import com.google.inject.Inject;
import com.google.inject.PrivateModule;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.midokura.midolman.config.ZookeeperConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.host.state.HostZkManager;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.ZkManager;
import com.midokura.midolman.state.zkManagers.BgpZkManager;
import com.midokura.midolman.state.zkManagers.BridgeDhcpZkManager;
import com.midokura.midolman.state.zkManagers.BridgeZkManager;
import com.midokura.midolman.state.zkManagers.ChainZkManager;
import com.midokura.midolman.state.zkManagers.PortZkManager;
import com.midokura.midolman.state.zkManagers.RouteZkManager;
import com.midokura.midolman.state.zkManagers.RouterZkManager;
import com.midokura.midolman.state.zkManagers.RuleZkManager;
import com.midokura.midostore.LocalMidostoreClient;
import com.midokura.midostore.MidostoreClient;
import com.midokura.midostore.services.MidostoreSetupService;

public class MidoStoreModule extends PrivateModule {

    private static final Logger log = LoggerFactory
        .getLogger(MidoStoreModule.class);

    @Override
    protected void configure() {
        binder().requireExplicitBindings();

        requireBinding(Directory.class);

        bindManagers();

        bind(MidostoreClient.class)
            .to(LocalMidostoreClient.class)
            .asEagerSingleton();
        expose(MidostoreClient.class);

        bind(MidostoreSetupService.class).in(Singleton.class);
        expose(MidostoreSetupService.class);
    }

    protected void bindManagers() {
        List<Class<? extends ZkManager>> managers = new ArrayList<Class<? extends ZkManager>>();

        managers.add(HostZkManager.class);
        managers.add(BgpZkManager.class);
        managers.add(RouterZkManager.class);
        managers.add(RouteZkManager.class);
        managers.add(RuleZkManager.class);
        managers.add(BridgeDhcpZkManager.class);
        managers.add(BridgeZkManager.class);
        managers.add(ChainZkManager.class);
        managers.add(PortZkManager.class);

        for (Class<? extends ZkManager> managerClass : managers) {
            bind(managerClass)
                .toProvider(new ZkManagerProvider(managerClass))
                .asEagerSingleton();
            expose(managerClass);
        }
    }

    private static class ZkManagerProvider<T extends ZkManager>
        implements Provider<T> {

        @Inject
        Directory directory;

        @Inject
        ZookeeperConfig config;

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
                throw new RuntimeException(
                    "Could not create zkManager of class: " + managerClass, e);
            }
        }
    }
}
