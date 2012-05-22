/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman;

import java.util.UUID;

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
     * @param controller is a reference on the controller that has this
     *                   observer registered
     * @param portNum    is the portNumber (as known by OVSDB)
     * @param portId     is the UUID of the port (from the OVSDB external ID key)
     * @param portName   is the port name (as known by OVSDB)
     */
    void addVirtualPort(VRNController controller,
                        int portNum, UUID portId, String portName);

    /**
     * Called when the controller has been notified that a port was just removed.
     *
     * @param controller is a reference to the controller that has this
     *                   observer registered.
     * @param portNum    is the portNumber (as known by OVSDB)
     * @param portId     is the UUID of the port (from the OVSDB external ID key)
     */
    void delVirtualPort(VRNController controller, int portNum, UUID portId);
}
