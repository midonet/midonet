/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.host.sensor;

import com.midokura.midolman.host.interfaces.InterfaceDescription;

import java.util.List;

public interface InterfaceSensor {

    /**
     * Given a list of interfaces on the system, traverses the list and updates each interface
     * with the data retrieved from the sensor.
     * The first InterfaceSensor will create the InterfaceDescriptions. The rest will just
     * update them.
     *
     * @param interfaces list of interfaces detected on the system, or null on first call
     * @return the input list of interfaces filled with the data the sensor has retrieved
     */
    public List<InterfaceDescription> updateInterfaceData(List<InterfaceDescription> interfaces);
}
