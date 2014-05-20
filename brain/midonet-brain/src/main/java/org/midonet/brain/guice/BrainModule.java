/**
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.brain.guice;

import com.google.inject.PrivateModule;
import org.midonet.config.ConfigProvider;

/**
 * Brain module configuration. This should declare top level services that must
 * be started up with the controller, and their dependencies.
 */
public class BrainModule extends PrivateModule {
    @Override
    protected void configure() {
        requireBinding(ConfigProvider.class);
    }
}
