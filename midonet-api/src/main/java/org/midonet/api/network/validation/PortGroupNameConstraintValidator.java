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
package org.midonet.api.network.validation;

import com.google.inject.Inject;
import org.midonet.api.network.PortGroup;
import org.midonet.api.validation.MessageProperty;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.cluster.DataClient;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

public class PortGroupNameConstraintValidator implements
        ConstraintValidator<IsUniquePortGroupName, PortGroup> {

    private final DataClient dataClient;

    @Inject
    public PortGroupNameConstraintValidator(DataClient dataClient) {
        this.dataClient = dataClient;
    }

    @Override
    public void initialize(IsUniquePortGroupName constraintAnnotation) {
    }

    @Override
    public boolean isValid(PortGroup value,
                           ConstraintValidatorContext context) {

        // Guard against a DTO that cannot be validated
        String tenantId = value.getTenantId();
        if (tenantId == null || value.getName() == null) {
            throw new IllegalArgumentException("Invalid port group passed in.");
        }

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                MessageProperty.IS_UNIQUE_PORT_GROUP_NAME).addNode("name")
                .addConstraintViolation();

        org.midonet.cluster.data.PortGroup portGroup = null;
        try {
            portGroup = dataClient.portGroupsGetByName(tenantId,
                    value.getName());
        } catch (StateAccessException e) {
            throw new RuntimeException("State access exception occurred in validation", e);
        } catch (SerializationException e) {
            throw new RuntimeException("Serialization exception occurred in validation", e);
        }

        // It's valid if the duplicate named portGroup does not exist, or
        // exists but it's the same portGroup.
        return (portGroup == null || portGroup.getId().equals(value.getId()));
    }
}
