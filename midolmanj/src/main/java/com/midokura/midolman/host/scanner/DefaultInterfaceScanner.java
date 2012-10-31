/*
 * Copyright 2012 Midokura Pte. Ltd.
 */
package com.midokura.midolman.host.scanner;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;
import com.midokura.midolman.host.interfaces.InterfaceDescription;
import com.midokura.midolman.host.sensor.*;
import com.midokura.netlink.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Default implementation for the interface scanning component.
 */

@Singleton
public class DefaultInterfaceScanner implements InterfaceScanner {

    ///////////////////////////////////////////////////////////////////////////
    // Attributes
    ///////////////////////////////////////////////////////////////////////////
    private final static Logger log =
        LoggerFactory.getLogger(DefaultInterfaceScanner.class);

    ///////////////////////////////////////////////////////////////////////////
    // Public methods
    ///////////////////////////////////////////////////////////////////////////
    List<InterfaceSensor> sensors = new ArrayList<InterfaceSensor>();

    @Inject
    // In this case we inject the Injector itself
    public DefaultInterfaceScanner(Injector injector) {
        // Always call first IpAddrInterfaceSensor, as it is the sensor who
        // will create the interfaces
        // getInstance will try to create an object of the type specified. If some
        // member is annotated with @Inject it will try to inject
        sensors.add(injector.getInstance(IpAddrInterfaceSensor.class));
        sensors.add(injector.getInstance(IpTuntapInterfaceSensor.class));
        sensors.add(injector.getInstance(DmesgInterfaceSensor.class));
        sensors.add(injector.getInstance(NetlinkInterfaceSensor.class));
    }

    @Override
    public synchronized InterfaceDescription[] scanInterfaces() {
        log.debug("Start scanning for interface data.");

        List<InterfaceDescription> interfaces = new ArrayList<InterfaceDescription>();

        for (InterfaceSensor sensor : sensors) {
            interfaces = sensor.updateInterfaceData(interfaces);
        }

        return interfaces.toArray(new InterfaceDescription[interfaces.size()]);
    }

    @Override
    public void scanInterfaces(Callback<List<InterfaceDescription>> callback) {
        List<InterfaceDescription> list = Arrays.asList(scanInterfaces());
        callback.onSuccess(list);
    }
}
