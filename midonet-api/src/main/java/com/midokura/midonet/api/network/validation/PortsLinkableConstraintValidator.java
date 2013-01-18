/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.network.validation;

import com.google.inject.Inject;
import com.midokura.midonet.api.network.Link;
import com.midokura.midonet.api.network.Port;
import com.midokura.midonet.api.network.PortFactory;
import com.midokura.midonet.api.validation.MessageProperty;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.cluster.DataClient;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

public class PortsLinkableConstraintValidator implements
        ConstraintValidator<PortsLinkable, Link> {

    private final DataClient dataClient;

    @Inject
    public PortsLinkableConstraintValidator(DataClient dataClient) {
        this.dataClient = dataClient;
    }

    @Override
    public void initialize(PortsLinkable constraintAnnotation) {
    }

    @Override
    public boolean isValid(Link value, ConstraintValidatorContext context) {

        // Guard against bad input
        if (value.getPortId() == null || value.getPeerId() == null) {
            throw new IllegalArgumentException("Invalid Link passed in.");
        }

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                MessageProperty.PORTS_LINKABLE).addNode("link")
                .addConstraintViolation();

        com.midokura.midonet.cluster.data.Port portData = null;
        com.midokura.midonet.cluster.data.Port peerPortData = null;
        try {
            portData = dataClient.portsGet(value.getPortId());
            if (portData == null) {
                throw new IllegalArgumentException("Port does not exist");
            }

            peerPortData = dataClient.portsGet(value.getPeerId());
            if (peerPortData == null) {
                throw new IllegalArgumentException("Peer port does not exist");
            }
        } catch (StateAccessException e) {
            throw new RuntimeException("Data access error while validating");
        }

        Port port = PortFactory.createPort(portData);
        Port peerPort = PortFactory.createPort(peerPortData);
        return port.isLinkable(peerPort);
    }
}
