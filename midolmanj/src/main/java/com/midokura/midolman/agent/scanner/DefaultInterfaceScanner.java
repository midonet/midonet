/*
 * Copyright 2012 Midokura Pte. Ltd.
 */
package com.midokura.midolman.agent.scanner;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.midokura.midolman.agent.sensor.InterfaceSensor;
import com.midokura.midolman.agent.sensor.IpAddrInterfaceSensor;
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
        sensors.add(injector.getInstance(IpAddrInterfaceSensor.class));
    }

    @Override
    public InterfaceDescription[] scanInterfaces() {
        log.debug("Start scanning for interface data.");

        List<InterfaceDescription> interfaces = new ArrayList<InterfaceDescription>();

        for (InterfaceSensor sensor : sensors) {
            interfaces = sensor.updateInterfaceData(interfaces);
        }

        return interfaces;
    }

}
