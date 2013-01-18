/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.network.validation;

import com.google.inject.Inject;
import com.midokura.midonet.api.network.Router;
import com.midokura.midonet.api.validation.MessageProperty;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.cluster.DataClient;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;


public class RouterNameConstraintValidator implements
        ConstraintValidator<IsUniqueRouterName, Router> {

    private final DataClient dataClient;

    @Inject
    public RouterNameConstraintValidator(DataClient dataClient) {
        this.dataClient = dataClient;
    }

    @Override
    public void initialize(IsUniqueRouterName constraintAnnotation) {
    }

    @Override
    public boolean isValid(Router value, ConstraintValidatorContext context) {

        // Guard against a DTO that cannot be validated
        String tenantId = value.getTenantId();
        if (tenantId == null || value.getName() == null) {
            throw new IllegalArgumentException("Invalid Router passed in.");
        }

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                MessageProperty.IS_UNIQUE_ROUTER_NAME).addNode("name")
                .addConstraintViolation();

        com.midokura.midonet.cluster.data.Router router = null;
        try {
            router = dataClient.routersGetByName(tenantId, value.getName());
        } catch (StateAccessException e) {
            throw new RuntimeException(
                    "State access exception occurred in validation");
        }

        // It's valid if the duplicate named router does not exist, or
        // exists but it's the same router.
        return (router == null || router.getId().equals(value.getId()));
    }
}
