/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.network;

import com.google.inject.AbstractModule;
import com.midokura.midolman.mgmt.filter.validation.ChainNameConstraintValidator;
import com.midokura.midolman.mgmt.network.validation.*;
import com.midokura.midolman.mgmt.validation.AllowedValueConstraintValidator;
import com.midokura.midolman.mgmt.validation.GuiceConstraintValidatorFactory;
import com.midokura.midolman.mgmt.validation.ValidatorProvider;
import com.midokura.midonet.cluster.DataClient;

import javax.validation.Validator;

/**
 *　Network module.
 */
public class NetworkModule extends AbstractModule {

    @Override
    protected void configure() {

        bind(BridgeNameConstraintValidator.class).asEagerSingleton();
        bind(ChainNameConstraintValidator.class).asEagerSingleton();

        requireBinding(DataClient.class);
        bind(RouteNextHopPortConstraintValidator.class).asEagerSingleton();

        bind(RouterNameConstraintValidator.class).asEagerSingleton();
        bind(PortsLinkableConstraintValidator.class).asEagerSingleton();
        bind(PortIdValidator.class).asEagerSingleton();

    }

}
