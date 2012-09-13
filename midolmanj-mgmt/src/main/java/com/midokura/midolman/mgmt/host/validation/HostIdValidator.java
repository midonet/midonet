/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.host.validation;

import com.google.inject.Inject;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.cluster.DataClient;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;
import java.util.UUID;

public class HostIdValidator implements
        ConstraintValidator<IsValidHostId, UUID> {

    private final DataClient dataClient;

    @Inject
    public HostIdValidator(DataClient dataClient) {
        this.dataClient = dataClient;
    }

    @Override
    public void initialize(IsValidHostId constraintAnnotation) {
    }

    @Override
    public boolean isValid(UUID value, ConstraintValidatorContext context) {

        if (value == null) {
            return false;
        }

        try {
            return dataClient.hostsExists(value);
        } catch (StateAccessException e) {
            throw new RuntimeException("Error while validation host");
        }

    }
}
