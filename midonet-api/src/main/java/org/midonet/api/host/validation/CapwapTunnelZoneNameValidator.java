/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.host.validation;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

import com.google.inject.Inject;
import org.midonet.api.host.CapwapTunnelZone;
import org.midonet.api.validation.MessageProperty;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.TunnelZone;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

// Unfortunately we're using org.hibernate.validator 4.3 which doesn't yet
// support cross-parameter validation. If this ever changes, check
// https://docs.jboss.org/hibernate/validator/5.0/reference/en-US/pdf/hibernate_validator_reference.pdf
// section 6.3 -- galo, 20130828
//
// This means that I can't figure out how to simply use a generic validator so
// I need one per type.
public class CapwapTunnelZoneNameValidator implements
                   ConstraintValidator<IsUniqueCapwapTunnelZoneName, CapwapTunnelZone> {

    private final DataClient dataClient;

    @Inject
    public CapwapTunnelZoneNameValidator(DataClient dataClient) {
        this.dataClient = dataClient;
    }

    @Override
    public void initialize(IsUniqueCapwapTunnelZoneName constraintAnnotation) {
    }

    @Override
    public boolean isValid(CapwapTunnelZone zone,
                           ConstraintValidatorContext context) {

        if (zone == null || zone.getName() == null || zone.getType() == null) {
            throw new IllegalArgumentException("Invalid tunnel zone passed in");
        }

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
            MessageProperty.IS_UNIQUE_TUNNEL_ZONE_NAME_TYPE
        ).addNode("name").addNode("type").addConstraintViolation();

        try {
            for (TunnelZone tz : dataClient.tunnelZonesGetAll()) {
                if (tz.getName().equalsIgnoreCase(zone.getName()) &&
                    tz.getType().equals(TunnelZone.Type.Capwap)) {
                    return false;
                }
            }
        } catch (StateAccessException e) {
            throw new RuntimeException(
                "Unable to get tunnel zones while validating tunnel zone", e);
        } catch (SerializationException e) {
            throw new RuntimeException("Unable to deserialize tunnel zones " +
                                       "while validating tunnel zone", e);
        }

        return true;

    }
}
