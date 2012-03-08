/*
 * Copyright 2012 Midokura Pte. Ltd.
 */
package com.midokura.midolman.agent.scanner;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;
import com.midokura.midolman.agent.sensor.InterfaceSensor;
import com.midokura.midolman.agent.sensor.IpAddrInterfaceSensor;
import com.midokura.midolman.agent.sensor.DmesgInterfaceSensor;
import com.midokura.midolman.agent.sensor.IpTuntapInterfaceSensor;
import com.midokura.midolman.agent.sensor.OvsDbInterfaceSensor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.agent.interfaces.InterfaceDescription;

import java.util.ArrayList;
import java.util.List;

/**
 * Default implementation for the interface scanning component.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 2/8/12
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
    public DefaultInterfaceScanner(Injector injector) {
        // Always call first IpAddrInterfaceSensor, as it is the sensor who
        // will create the interfaces
        sensors.add(injector.getInstance(IpAddrInterfaceSensor.class));
        sensors.add(injector.getInstance(IpTuntapInterfaceSensor.class));
        sensors.add(injector.getInstance(DmesgInterfaceSensor.class));
        sensors.add(injector.getInstance(OvsDbInterfaceSensor.class));
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
}
