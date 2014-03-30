/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.host.sensor;

import org.midonet.midolman.host.interfaces.InterfaceDescription;

import java.util.Set;

public interface InterfaceSensor {

    /**
     * Given a list of interfaces on the system, traverses the list and updates each interface
     * with the data retrieved from the sensor.
     * The first InterfaceSensor will create the InterfaceDescriptions. The rest will just
     * update them.
     *
     * @param interfaces list of interfaces detected on the system, or null on first call
     */
    public void updateInterfaceData(Set<InterfaceDescription> interfaces);
}
