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
package org.midonet.api.filter.validation;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

import com.google.inject.Inject;
import org.midonet.api.filter.Chain;
import org.midonet.api.validation.MessageProperty;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.cluster.DataClient;

public class ChainNameConstraintValidator implements
        ConstraintValidator<IsUniqueChainName, Chain> {

    private final DataClient dataClient;

    @Inject
    public ChainNameConstraintValidator(DataClient dataClient) {
        this.dataClient = dataClient;
    }

    @Override
    public void initialize(IsUniqueChainName constraintAnnotation) {
    }

    @Override
    public boolean isValid(Chain value, ConstraintValidatorContext context) {

        // Guard against a DTO that cannot be validated
        String tenantId = value.getTenantId();
        if (tenantId == null || value.getName() == null) {
            throw new IllegalArgumentException("Invalid Chain passed in.");
        }

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                MessageProperty.IS_UNIQUE_CHAIN_NAME).addNode("name")
                .addConstraintViolation();

        org.midonet.cluster.data.Chain chain;
        try {
            chain = dataClient.chainsGetByName(value.getTenantId(),
                    value.getName());
        } catch (StateAccessException e) {
            throw new RuntimeException("State access exception occurred in validation", e);
        } catch (SerializationException e) {
            throw new RuntimeException("Serialization exception occurred in validation", e);
        }

        // It's valid if the duplicate named chain does not exist, or
        // exists but it's the same chain.
        return (chain == null || chain.getId().equals(value.getId()));
    }
}
