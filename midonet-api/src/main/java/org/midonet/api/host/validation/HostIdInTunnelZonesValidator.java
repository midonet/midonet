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
