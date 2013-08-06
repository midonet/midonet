/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network.validation;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

import com.google.inject.Inject;

import org.midonet.cluster.data.ports.BridgePort;
import org.midonet.midolman.serialization.SerializationException;
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

        if (value.getPortId() == null)
            return portNotOnBridgeError(context);

        // Port must belong to the specified bridge.
        Port<?, ?> p = null;
        try {
            p = dataClient.portsGet(value.getPortId());
            if (p == null || !p.getDeviceId().equals(value.getBridgeId()))
                return portNotOnBridgeError(context);
        } catch (StateAccessException|SerializationException e) {
            return portNotOnBridgeError(context);
        }

        // If the port is tagged with a VLAN, the MAC-port mapping must
        // specify that VLAN ID.
        if (p instanceof BridgePort) {
            BridgePort.Data data = ((BridgePort)p).getData();
            if (data.vlanId != null && !data.vlanId.equals(value.getVlanId())) {
                String msg = MessageProperty.getMessage(
                        MessageProperty.VLAN_ID_MATCHES_PORT_VLAN_ID,
                        data.vlanId);
                context.buildConstraintViolationWithTemplate(msg)
                        .addNode("macPort").addConstraintViolation();
                return false;
            }
        }

        return true;
    }

    private boolean portNotOnBridgeError(ConstraintValidatorContext context) {
        context.buildConstraintViolationWithTemplate(
                MessageProperty.MAC_PORT_ON_BRIDGE)
                        .addNode("macPort").addConstraintViolation();
        return false;
    }
}
