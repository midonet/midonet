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
package org.midonet.midolman.host.guice;

import com.google.inject.PrivateModule;
import com.google.inject.Scopes;
import org.midonet.config.ConfigProvider;
import org.midonet.midolman.host.config.HostConfig;
import org.midonet.midolman.host.sensor.IpAddrInterfaceSensor;
import org.midonet.midolman.host.sensor.IpTuntapInterfaceSensor;
import org.midonet.midolman.host.sensor.NetlinkInterfaceSensor;
import org.midonet.midolman.host.sensor.SysfsInterfaceSensor;
import org.midonet.midolman.host.services.HostService;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.host.updater.DefaultInterfaceDataUpdater;
import org.midonet.midolman.host.updater.InterfaceDataUpdater;
import org.midonet.midolman.services.HostIdProviderService;

import javax.inject.Singleton;

/**
 * Module to configure dependencies for the host.
 */
public class HostModule extends PrivateModule {

    @Override
    protected void configure() {
        binder().requireExplicitBindings();

        //bind(InterfaceScanner.class).to(DefaultInterfaceScanner.class);
        bind(InterfaceDataUpdater.class).to(DefaultInterfaceDataUpdater.class);

        //expose(InterfaceScanner.class);
        expose(InterfaceDataUpdater.class);

        requireBinding(ConfigProvider.class);
        bind(HostConfig.class)
                .toProvider(HostConfigProvider.class)
                .asEagerSingleton();
        expose(HostConfig.class);
        expose(HostIdProviderService.class);

        // TODO: uncomment this when the direct dependency on HostZKManager has been removed
        // requireBinding(Client.class);
        requireBinding(HostZkManager.class);

        bind(HostIdProviderService.class)
            .to(HostService.class)
            .in(Singleton.class);
        expose(HostIdProviderService.class);

        bind(HostService.class)
            .in(Singleton.class);

        expose(HostService.class);

        bind(IpAddrInterfaceSensor.class);
        expose(IpAddrInterfaceSensor.class);
        bind(IpTuntapInterfaceSensor.class);
        expose(IpTuntapInterfaceSensor.class);
        bind(SysfsInterfaceSensor.class);
        expose(SysfsInterfaceSensor.class);
        bind(NetlinkInterfaceSensor.class);
        expose(NetlinkInterfaceSensor.class);
    }
}
