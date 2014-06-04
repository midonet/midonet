/**
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.brain.guice;

import com.google.inject.PrivateModule;
import com.google.inject.Provider;

import org.opendaylight.ovsdb.plugin.ConfigurationService;
import org.opendaylight.ovsdb.plugin.ConnectionService;
import org.opendaylight.ovsdb.plugin.InventoryService;

import org.midonet.brain.southbound.vtep.VtepDataClient;
import org.midonet.config.ConfigProvider;

/**
 * Brain module configuration. This should declare top level services that must
 * be started up with the controller, and their dependencies.
 */
public class BrainModule extends PrivateModule {
    @Override
    protected void configure() {
        requireBinding(ConfigProvider.class);
        requireBinding(ConfigurationService.class);
        requireBinding(ConnectionService.class);
        requireBinding(InventoryService.class);
        requireBinding(VtepDataClient.class);
    }

    public class InventoryServiceProvider implements Provider<InventoryService> {
        @Override
        public InventoryService get() {
            return new InventoryService();
        }
    }

    public class ConnectionServiceProvider implements Provider<ConnectionService> {
        @Override
        public ConnectionService get() {
            return new ConnectionService();
        }
    }

    public class ConfigurationServiceProvider implements Provider<ConfigurationService> {
        @Override
        public ConfigurationService get() {
            return new ConfigurationService();
        }
    }
}
