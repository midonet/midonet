/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.api.network.validation;

import java.util.UUID;
import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

import com.google.inject.Inject;
import org.midonet.cluster.DataClient;
import org.midonet.midolman.state.StateAccessException;

public class RouterIdValidator implements
        ConstraintValidator<IsValidPortId, UUID> {

    private final DataClient dataClient;

    @Inject
    public RouterIdValidator(DataClient dataClient) {
        this.dataClient = dataClient;
    }

    @Override
    public void initialize(IsValidPortId constraintAnnotation) {
    }

    @Override
    public boolean isValid(UUID value, ConstraintValidatorContext context) {
        if (value == null) {
            return false;
        }
        try {
            return dataClient.routerExists(value);
        } catch (StateAccessException e) {
            throw new RuntimeException("Error while validating router", e);
        }

    }
}
