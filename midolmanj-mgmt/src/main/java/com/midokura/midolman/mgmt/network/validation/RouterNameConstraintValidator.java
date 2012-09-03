/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.network.validation;

import com.google.inject.Inject;
import com.midokura.midolman.mgmt.validation.MessageProperty;
import com.midokura.midolman.mgmt.network.Router;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.cluster.DataClient;
import com.midokura.midonet.cluster.data.Router.Property;

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
        if (tenantId == null && value.getId() == null) {
            throw new IllegalArgumentException("Invalid Router passed in.");
        }

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                MessageProperty.IS_UNIQUE_ROUTER_NAME).addNode("name")
                .addConstraintViolation();

        // For updates, the tenant ID is not given, so get it
        if (value.getId() != null) {
            try {
                com.midokura.midonet.cluster.data.Router router =
                        dataClient.routersGet(value.getId());
                tenantId = router.getProperty(Property.tenant_id);
            } catch (StateAccessException e) {
                throw new RuntimeException(
                        "State access exception occurred in validation");
            }
        }

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
