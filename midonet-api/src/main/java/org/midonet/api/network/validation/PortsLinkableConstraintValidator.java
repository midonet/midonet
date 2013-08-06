/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network.validation;

import com.google.inject.Inject;
import org.midonet.api.network.Link;
import org.midonet.api.network.Port;
import org.midonet.api.network.PortFactory;
import org.midonet.api.validation.MessageProperty;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.cluster.DataClient;

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

        org.midonet.cluster.data.Port portData = null;
        org.midonet.cluster.data.Port peerPortData = null;
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
        } catch (SerializationException e) {
            throw new RuntimeException(
                    "Serialization exception occurred in validation");
        }

        Port port = PortFactory.convertToApiPort(portData);
        Port peerPort = PortFactory.convertToApiPort(peerPortData);
        return port.isLinkable(peerPort);
    }
}
