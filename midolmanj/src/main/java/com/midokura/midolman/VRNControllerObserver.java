/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman;

import java.util.UUID;

import com.midokura.midolman.packets.MAC;

/**
 * Observer for Virtual Router Network events that happen inside a specific
 * controller instance (aka on the current machine).
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 5/2/12
 */
public interface VRNControllerObserver {

    /**
     * Called when a port has just become visible to a controller.
     *
     * @param portNum is the portNumber (as known by OVS)
     * @param name is the port name (as known by OVS)
     * @param addr is the mac address
     * @param portId is the UUID of the port (as known by OVS)
     */
    void addVirtualPort(int portNum, String name, MAC addr, UUID portId);

    /**
     * Called when the controller has been notified that a port was just removed.
     *
     * @param portNum is the portNumber (as known by OVS)
     * @param portId is the UUID of the port (as known by OVS)
     */
    void delVirtualPort(int portNum, UUID portId);
}
