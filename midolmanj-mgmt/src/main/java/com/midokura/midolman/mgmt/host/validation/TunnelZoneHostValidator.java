/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.host.validation;

import com.google.inject.Inject;
import com.midokura.midolman.mgmt.host.CapwapTunnelZoneHost;
import com.midokura.midolman.mgmt.host.GreTunnelZoneHost;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.mgmt.host.TunnelZoneHost;
import com.midokura.midonet.cluster.DataClient;
import com.midokura.midonet.cluster.data.TunnelZone;
import com.midokura.midonet.cluster.data.zones.CapwapTunnelZone;
import com.midokura.midonet.cluster.data.zones.GreTunnelZone;

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

            TunnelZone tz = dataClient.tunnelZonesGet(tzh.getTunnelZoneId());
            if (tz instanceof GreTunnelZone && !(tzh instanceof GreTunnelZoneHost))
                return false;
            if (tz instanceof CapwapTunnelZone && !(tzh instanceof CapwapTunnelZoneHost))
                return false;

            return dataClient.tunnelZonesGetMembership(
                    tzh.getTunnelZoneId(), tzh.getHostId()) == null;
        } catch (StateAccessException e) {
            throw new RuntimeException("Error while validating tunnel zone host");
        }

    }
}
