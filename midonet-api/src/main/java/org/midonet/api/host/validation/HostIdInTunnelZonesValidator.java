/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.api.host.validation;

import com.google.inject.Inject;
import org.midonet.cluster.DataClient;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

import java.util.UUID;
import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;


public class HostIdInTunnelZonesValidator implements
        ConstraintValidator<IsHostIdInAnyTunnelZone, UUID> {

    private final DataClient dataClient;

    @Inject
    public HostIdInTunnelZonesValidator(DataClient dataClient) {
        this.dataClient = dataClient;
    }

    @Override
    public void initialize(IsHostIdInAnyTunnelZone constraintAnnotation) {
    }

    @Override
    public boolean isValid(UUID value, ConstraintValidatorContext context) {

        if (value == null) {
            return false;
        }

        try {
            return dataClient.hostsExists(value) &&
                    dataClient.tunnelZonesContainHost(value);
        } catch (StateAccessException | SerializationException e) {
            throw new RuntimeException("Error while validation host", e);
        }

    }
}
