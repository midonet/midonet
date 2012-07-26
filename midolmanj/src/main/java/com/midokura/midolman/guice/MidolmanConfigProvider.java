/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice;

import com.google.inject.Inject;
import com.google.inject.Provider;

import com.midokura.config.ConfigProvider;
import com.midokura.midolman.config.MidolmanConfig;

/**
 * // TODO: mtoader ! Please explain yourself.
 */
public class MidolmanConfigProvider implements Provider<MidolmanConfig> {

    @Inject
    ConfigProvider configProvider;

    @Override
    public MidolmanConfig get() {
        return configProvider.getConfig(MidolmanConfig.class);
    }
}
