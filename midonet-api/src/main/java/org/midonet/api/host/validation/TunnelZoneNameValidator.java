/**
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.host.validation;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

import com.google.inject.Inject;
import org.midonet.api.validation.MessageProperty;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.TunnelZone;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

public class TunnelZoneNameValidator implements
    ConstraintValidator<UniqueTunnelZoneName, String> {

    private final DataClient dataClient;

    @Inject
    public TunnelZoneNameValidator(DataClient dataClient) {
        this.dataClient = dataClient;
    }

    @Override
    public void initialize(UniqueTunnelZoneName constraintAnnotation) {
    }

    @Override
    public boolean isValid(String tzName, ConstraintValidatorContext context) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
            MessageProperty.UNIQUE_TUNNEL_ZONE_NAME_TYPE
        ).addConstraintViolation();

        try {
            for (TunnelZone<?, ?> tz : dataClient.tunnelZonesGetAll()) {
                if (tz.getName().equalsIgnoreCase(tzName)) {
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
