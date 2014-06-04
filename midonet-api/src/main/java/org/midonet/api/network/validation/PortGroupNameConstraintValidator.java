/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network.validation;

import com.google.inject.Inject;
import org.midonet.api.network.PortGroup;
import org.midonet.api.validation.MessageProperty;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.cluster.DataClient;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

public class PortGroupNameConstraintValidator implements
        ConstraintValidator<IsUniquePortGroupName, PortGroup> {

    private final DataClient dataClient;

    @Inject
    public PortGroupNameConstraintValidator(DataClient dataClient) {
        this.dataClient = dataClient;
    }

    @Override
    public void initialize(IsUniquePortGroupName constraintAnnotation) {
    }

    @Override
    public boolean isValid(PortGroup value,
                           ConstraintValidatorContext context) {

        // Guard against a DTO that cannot be validated
        String tenantId = value.getTenantId();
        if (tenantId == null || value.getName() == null) {
            throw new IllegalArgumentException("Invalid port group passed in.");
        }

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                MessageProperty.IS_UNIQUE_PORT_GROUP_NAME).addNode("name")
                .addConstraintViolation();

        org.midonet.cluster.data.PortGroup portGroup = null;
        try {
            portGroup = dataClient.portGroupsGetByName(tenantId,
                    value.getName());
        } catch (StateAccessException e) {
            throw new RuntimeException("State access exception occurred in validation", e);
        } catch (SerializationException e) {
            throw new RuntimeException("Serialization exception occurred in validation", e);
        }

        // It's valid if the duplicate named portGroup does not exist, or
        // exists but it's the same portGroup.
        return (portGroup == null || portGroup.getId().equals(value.getId()));
    }
}
