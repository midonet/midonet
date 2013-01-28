/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.network.validation;

import com.google.inject.Inject;
import com.midokura.midonet.api.validation.MessageProperty;
import com.midokura.midonet.api.network.Bridge;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.cluster.DataClient;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

public class BridgeNameConstraintValidator implements
        ConstraintValidator<IsUniqueBridgeName, Bridge> {

    private final DataClient dataClient;

    @Inject
    public BridgeNameConstraintValidator(DataClient dataClient) {
        this.dataClient = dataClient;
    }

    @Override
    public void initialize(IsUniqueBridgeName constraintAnnotation) {
    }

    @Override
    public boolean isValid(Bridge value, ConstraintValidatorContext context) {

        // Guard against a DTO that cannot be validated
        String tenantId = value.getTenantId();
        if (tenantId == null || value.getName() == null) {
            throw new IllegalArgumentException("Invalid Bridge passed in.");
        }

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                MessageProperty.IS_UNIQUE_BRIDGE_NAME).addNode("name")
                .addConstraintViolation();

        com.midokura.midonet.cluster.data.Bridge bridge = null;
        try {
            bridge = dataClient.bridgesGetByName(tenantId, value.getName());
        } catch (StateAccessException e) {
            throw new RuntimeException(
                    "State access exception occurred in validation");
        }

        // It's valid if the duplicate named bridge does not exist, or
        // exists but it's the same bridge.
        return (bridge == null || bridge.getId().equals(value.getId()));
    }
}
