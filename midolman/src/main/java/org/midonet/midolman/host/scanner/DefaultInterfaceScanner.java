/*
 * Copyright 2012 Midokura Pte. Ltd.
 */
package org.midonet.midolman.host.scanner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.inject.Named;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.host.interfaces.InterfaceDescription;
import org.midonet.midolman.host.sensor.InterfaceSensor;
import org.midonet.midolman.host.sensor.IpAddrInterfaceSensor;
import org.midonet.midolman.host.sensor.IpTuntapInterfaceSensor;
import org.midonet.midolman.host.sensor.SysfsInterfaceSensor;
import org.midonet.midolman.host.sensor.NetlinkInterfaceSensor;
import org.midonet.netlink.Callback;
import org.midonet.util.eventloop.Reactor;

/**
 * Default implementation for the interface scanning component.
 */
@Singleton
public class DefaultInterfaceScanner implements InterfaceScanner {
    public static final String INTERFACE_REACTOR = "interface reactor" ;

    @Inject
    @Named(INTERFACE_REACTOR)
    Reactor reactor;

    private final static Logger log =
        LoggerFactory.getLogger(DefaultInterfaceScanner.class);

    List<InterfaceSensor> sensors = new ArrayList<InterfaceSensor>();

    @Inject
    // In this case we inject the Injector itself
    public DefaultInterfaceScanner(Injector injector) {
        // Always call first IpAddrInterfaceSensor, as it is the sensor who
        // will create the interfaces
        // getInstance will try to create an object of the type specified.
        // If some member is annotated with @Inject it will try to inject
        sensors.add(injector.getInstance(IpAddrInterfaceSensor.class));
        sensors.add(injector.getInstance(IpTuntapInterfaceSensor.class));
        sensors.add(injector.getInstance(SysfsInterfaceSensor.class));
        sensors.add(injector.getInstance(NetlinkInterfaceSensor.class));
    }

    @Override
    public synchronized List<InterfaceDescription> scanInterfaces() {
        List<InterfaceDescription> interfaces = new ArrayList<>();

        for (InterfaceSensor sensor : sensors) {
            interfaces = sensor.updateInterfaceData(interfaces);
        }

        return interfaces;
    }

    @Override
    public void scanInterfaces(
            final Callback<List<InterfaceDescription>> callback) {
        reactor.submit(
            new Runnable() {
                @Override
                public void run() {
                    callback.onSuccess(scanInterfaces());
                }
            }
        );
    }

    public void shutDownNow() {
        reactor.shutDownNow();
    }
}
