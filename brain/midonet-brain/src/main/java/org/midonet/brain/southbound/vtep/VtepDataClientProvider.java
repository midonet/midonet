/*
- * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
- */

package org.midonet.brain.southbound.vtep;

import com.google.inject.Inject;
import com.google.inject.Provider;

import org.opendaylight.ovsdb.plugin.ConfigurationService;
import org.opendaylight.ovsdb.plugin.ConnectionService;
import org.opendaylight.ovsdb.plugin.InventoryService;

public class VtepDataClientProvider {

    @Inject
    private Provider<ConnectionService> cnxnServiceProvider;

    @Inject
    private Provider<ConfigurationService> cfgServiceProvider;

    @Inject
    private Provider<InventoryService> invServiceProvider;


    public VtepDataClient get() {
        return new VtepDataClientImpl(cfgServiceProvider.get(),
                                      cnxnServiceProvider.get(),
                                      invServiceProvider.get());
    }

}

