/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network.validation;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

import com.google.inject.Inject;

import org.midonet.midolman.state.StateAccessException;
import org.midonet.api.network.MacPort;
import org.midonet.api.validation.MessageProperty;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Port;

public class MacPortConstraintValidator implements
        ConstraintValidator<MacPortValid, MacPort> {
    @Inject
    DataClient dataClient;

    @Override
    public void initialize(MacPortValid constraintAnnotation) {
    }

    @Override
    public boolean isValid(MacPort value, ConstraintValidatorContext context) {

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                MessageProperty.MAC_PORT_ON_BRIDGE)
                .addNode("macPort").addConstraintViolation();

        if (value.getPortId() == null)
            return false;

        try {
            Port<?, ?> p = dataClient.portsGet(value.getPortId());
            return p != null && p.getDeviceId().equals(value.getBridgeId());
        } catch (StateAccessException e) {
            return false;
        }
    }
}
