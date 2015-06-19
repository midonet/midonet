/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.api.network.validation;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

import com.google.inject.Inject;

import org.midonet.api.network.Link;
import org.midonet.api.network.Port;
import org.midonet.api.network.PortFactory;
import org.midonet.cluster.rest_api.validation.MessageProperty;
import org.midonet.cluster.DataClient;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

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
                MessageProperty.PORTS_LINKABLE).addPropertyNode("link")
                .addConstraintViolation();

        org.midonet.cluster.data.Port<?, ?> portData = null;
        org.midonet.cluster.data.Port<?, ?> peerPortData = null;
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
            throw new RuntimeException("Data access error while validating", e);
        } catch (SerializationException e) {
            throw new RuntimeException("Serialization exception occurred in validation", e);
        }

        Port port = PortFactory.convertToApiPort(portData);
        Port peerPort = PortFactory.convertToApiPort(peerPortData);
        return port.isLinkable(peerPort);
    }
}
