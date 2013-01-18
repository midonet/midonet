/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.network.validation;

import com.google.inject.Inject;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.cluster.DataClient;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;
import java.util.UUID;

public class PortGroupIdValidator implements
        ConstraintValidator<IsValidPortGroupId, UUID> {

    private final DataClient dataClient;

    @Inject
    public PortGroupIdValidator(DataClient dataClient) {
        this.dataClient = dataClient;
    }

    @Override
    public void initialize(IsValidPortGroupId constraintAnnotation) {
    }

    @Override
    public boolean isValid(UUID value, ConstraintValidatorContext context) {

        if (value == null) {
            return false;
        }

        try {
            return dataClient.portGroupsExists(value);
        } catch (StateAccessException e) {
            throw new RuntimeException("Error while validation port");
        }

    }
}
