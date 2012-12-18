/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.host.validation;

import com.google.inject.Inject;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.mgmt.host.TunnelZoneHost;
import com.midokura.midonet.cluster.DataClient;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

public class TunnelZoneHostValidator implements
        ConstraintValidator<IsUniqueTunnelZoneMember, TunnelZoneHost> {

    private final DataClient dataClient;

    @Inject
    public TunnelZoneHostValidator(DataClient dataClient) {
        this.dataClient = dataClient;
    }

    @Override
    public void initialize(IsUniqueTunnelZoneMember constraintAnnotation) {
    }

    @Override
    public boolean isValid(TunnelZoneHost tzh, ConstraintValidatorContext context) {

        if (tzh == null) {
            return false;
        }

        try {
            if (! dataClient.tunnelZonesExists(tzh.getTunnelZoneId()))
                return false;
            return dataClient.tunnelZonesGetMembership(
                    tzh.getTunnelZoneId(), tzh.getHostId()) == null;
        } catch (StateAccessException e) {
            throw new RuntimeException("Error while validating tunnel zone host");
        }

    }
}
