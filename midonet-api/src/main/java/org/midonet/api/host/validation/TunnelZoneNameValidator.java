/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
            for (TunnelZone tz : dataClient.tunnelZonesGetAll()) {
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
