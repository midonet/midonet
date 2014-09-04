/**
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.guice;

import com.google.inject.PrivateModule;

import org.opendaylight.ovsdb.plugin.ConfigurationService;
import org.opendaylight.ovsdb.plugin.ConnectionService;
import org.opendaylight.ovsdb.plugin.InventoryService;

import org.midonet.brain.southbound.vtep.VtepDataClientFactory;
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
        requireBinding(VtepDataClientFactory.class);
    }
}
