/*
 * Copyright 2012 Midokura PTE LTD.
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
