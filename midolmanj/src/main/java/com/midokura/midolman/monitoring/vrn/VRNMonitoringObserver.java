/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.monitoring.vrn;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.monitoring.metrics.vrn.VifMetrics;
import com.midokura.midolman.vrn.VRNController;
import com.midokura.midolman.vrn.VRNControllerObserver;

/**
 * Simple observer to watch ports getting up and down on a virtual routing
 * network. It will enable/disable metrics collection based on port status.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 5/3/12
 */
public class VRNMonitoringObserver implements VRNControllerObserver {

    private static final Logger log = LoggerFactory
        .getLogger(VRNMonitoringObserver.class);

    public void addVirtualPort(VRNController controller,
                               int portNum, UUID portId, String portName) {
        log.debug("Virtual port with ID {} came up. Enabling metrics.", portId);

        VifMetrics.enableVirtualPortMetrics(controller, (short) portNum, portId, portName);
    }

    @Override
    public void delVirtualPort(VRNController controller,
                               int portNum, UUID portId) {
        log.debug("Virtual port with id {} went down. Disabling metrics.",
                  portId);

        VifMetrics.disableVirtualPortMetrics(controller, portNum, portId);
    }
}
