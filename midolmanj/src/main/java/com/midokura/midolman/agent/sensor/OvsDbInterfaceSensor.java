/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.agent.sensor;

import java.util.List;

import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.agent.config.HostAgentConfiguration;
import com.midokura.midolman.agent.interfaces.InterfaceDescription;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchException;
import static com.midokura.midolman.agent.state.HostDirectory.Interface.PropertyKeys;

public class OvsDbInterfaceSensor implements InterfaceSensor {

    private static final Logger log = LoggerFactory
        .getLogger(OvsDbInterfaceSensor.class);

    @Inject
    OpenvSwitchDatabaseConnection ovsdb;

    @Inject
    HostAgentConfiguration configuration;

    @Override
    public List<InterfaceDescription> updateInterfaceData(List<InterfaceDescription> interfaces) {
        for (InterfaceDescription interfaceDescription : interfaces) {

            try {
                String portExternalId = ovsdb.getPortExternalId(
                    interfaceDescription.getName(),
                    configuration.getMidolmanExternalIdKey());

                if (portExternalId != null) {
                    interfaceDescription.getProperties()
                                        .put(
                                            PropertyKeys.midonet_port_id.name(),
                                            portExternalId);

                    log.info("Updated interface: " + interfaceDescription);
                }

            } catch (OpenvSwitchException.NotFoundException ex) {
                // ignore the exception since
            }
            // Only update interfaces were the endpoint hasn't been already set
            if (interfaceDescription.getEndpoint() != InterfaceDescription.Endpoint.UNKNOWN) {
                // We already got an endpoint classification from the previous sensor
                // Skip this interface
                continue;
            }

            if (ovsdb.hasBridge(interfaceDescription.getName())) {
                // this is a bridge interface
                interfaceDescription.setEndpoint(InterfaceDescription.Endpoint.BRIDGE);
            }
        }

        return interfaces;
    }
}
