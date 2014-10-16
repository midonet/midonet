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

import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.api.host.TunnelZoneHost;
import org.midonet.cluster.DataClient;

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

            return dataClient.tunnelZonesGetMembership(
                    tzh.getTunnelZoneId(), tzh.getHostId()) == null;
        } catch (StateAccessException e) {
            throw new RuntimeException("State Access Error while validating tunnel zone host", e);
        } catch (SerializationException e) {
            throw new RuntimeException("Serialization Error while validating tunnel zone host", e);
        }

    }
}
