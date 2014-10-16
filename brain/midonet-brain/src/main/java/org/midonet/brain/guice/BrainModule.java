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
