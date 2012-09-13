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

public class TunnelZoneIdValidator implements
        ConstraintValidator<IsValidTunnelZoneId, UUID> {

    private final DataClient dataClient;

    @Inject
    public TunnelZoneIdValidator(DataClient dataClient) {
        this.dataClient = dataClient;
    }

    @Override
    public void initialize(IsValidTunnelZoneId constraintAnnotation) {
    }

    @Override
    public boolean isValid(UUID value, ConstraintValidatorContext context) {

        if (value == null) {
            return false;
        }

        try {
            return dataClient.tunnelZonesExists(value);
        } catch (StateAccessException e) {
            throw new RuntimeException("Error while validation tunnel zone");
        }

    }
}
