/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network.validation;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

import com.google.inject.Inject;
import org.midonet.api.network.VlanBridge;
import org.midonet.api.validation.MessageProperty;
import org.midonet.cluster.DataClient;
import org.midonet.midolman.state.StateAccessException;

public class VlanBridgeNameConstraintValidator implements
        ConstraintValidator<IsUniqueVlanBridgeName, VlanBridge> {

    private final DataClient dataClient;

    @Inject
    public VlanBridgeNameConstraintValidator(DataClient dataClient) {
        this.dataClient = dataClient;
    }

    @Override
    public void initialize(IsUniqueVlanBridgeName constraintAnnotation) {
    }

    @Override
    public boolean isValid(VlanBridge value, ConstraintValidatorContext ctx) {

        // Guard against a DTO that cannot be validated
        String tenantId = value.getTenantId();
        if (tenantId == null || value.getName() == null) {
            throw new IllegalArgumentException("Invalid VlanBridge passed in.");
        }

        ctx.disableDefaultConstraintViolation();
        ctx.buildConstraintViolationWithTemplate(
            MessageProperty.IS_UNIQUE_VLAN_BRIDGE_NAME).addNode("name")
                                                      .addConstraintViolation();

        org.midonet.cluster.data.VlanAwareBridge bridge = null;
        try {
            bridge = dataClient.vlanBridgesGetByName(tenantId, value.getName());
        } catch (StateAccessException e) {
            throw new RuntimeException(
                    "State access exception occurred in validation");
        }

        // It's valid if the duplicate named bridge does not exist, or
        // exists but it's the same bridge.
        return (bridge == null || bridge.getId().equals(value.getId()));
    }
}
