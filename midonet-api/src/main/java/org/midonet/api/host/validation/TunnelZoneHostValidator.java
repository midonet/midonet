/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.host.validation;

import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.api.host.CapwapTunnelZoneHost;
import org.midonet.api.host.GreTunnelZoneHost;
import org.midonet.api.host.TunnelZoneHost;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.TunnelZone;
import org.midonet.cluster.data.zones.CapwapTunnelZone;
import org.midonet.cluster.data.zones.GreTunnelZone;

import com.google.inject.Inject;
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

            TunnelZone<?, ?> tz = dataClient.tunnelZonesGet(tzh.getTunnelZoneId());
            if (tz instanceof GreTunnelZone && !(tzh instanceof GreTunnelZoneHost))
                return false;
            if (tz instanceof CapwapTunnelZone && !(tzh instanceof CapwapTunnelZoneHost))
                return false;

            return dataClient.tunnelZonesGetMembership(
                    tzh.getTunnelZoneId(), tzh.getHostId()) == null;
        } catch (StateAccessException e) {
            throw new RuntimeException("State Access Error while validating tunnel zone host", e);
        } catch (SerializationException e) {
            throw new RuntimeException("Serialization Error while validating tunnel zone host", e);
        }

    }
}
