/*
 * Copyright 2012 Midokura Pte. Ltd.
 */
package com.midokura.midolman.host.scanner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.inject.Named;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.host.interfaces.InterfaceDescription;
import com.midokura.midolman.host.sensor.DmesgInterfaceSensor;
import com.midokura.midolman.host.sensor.InterfaceSensor;
import com.midokura.midolman.host.sensor.IpAddrInterfaceSensor;
import com.midokura.midolman.host.sensor.IpTuntapInterfaceSensor;
import com.midokura.midolman.host.sensor.NetlinkInterfaceSensor;
import com.midokura.netlink.Callback;
import com.midokura.util.eventloop.Reactor;

/**
 * Default implementation for the interface scanning component.
 */

@Singleton
public class DefaultInterfaceScanner implements InterfaceScanner {
    public static final String INTERFACE_REACTOR = "interface reactor" ;

    @Inject
    @Named(INTERFACE_REACTOR)
    Reactor reactor;

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
        List<InterfaceDescription> interfaces = new ArrayList<InterfaceDescription>();

        for (InterfaceSensor sensor : sensors) {
            interfaces = sensor.updateInterfaceData(interfaces);
        }

        return interfaces.toArray(new InterfaceDescription[interfaces.size()]);
    }

    @Override
    public void scanInterfaces(final Callback<List<InterfaceDescription>> callback) {
        if (!reactor.isShutDownOrTerminated()) {
            reactor.submit(
                new Runnable() {
                    @Override
                    public void run() {
                        List<InterfaceDescription> list = Arrays.asList(scanInterfaces());
                        callback.onSuccess(list);
                    }
                }
            );
        } else {
            log.error("Failure, couldn't submit the task {}, executor stopped", callback);
        }
    }

    public void shutDownNow() {
        reactor.shutDownNow();
    }
}
