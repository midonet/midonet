/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network.validation;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

import com.google.inject.Inject;
import org.midonet.api.network.Bridge;
import org.midonet.api.validation.MessageProperty;
import org.midonet.cluster.DataClient;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

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

        org.midonet.cluster.data.Bridge bridge = null;
        try {
            bridge = dataClient.bridgesGetByName(tenantId, value.getName());
        } catch (StateAccessException e) {
            throw new RuntimeException(
                    "State access exception occurred in validation");
        } catch (SerializationException e) {
            throw new RuntimeException(
                    "Serialization exception occurred in validation");
        }

        // It's valid if the duplicate named bridge does not exist, or
        // exists but it's the same bridge.
        return (bridge == null || bridge.getId().equals(value.getId()));
    }
}
