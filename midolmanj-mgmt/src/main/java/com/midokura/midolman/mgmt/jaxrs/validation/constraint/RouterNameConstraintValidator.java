/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.jaxrs.validation.constraint;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

import com.midokura.midolman.mgmt.data.dao.RouterDao;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.jaxrs.validation.MessageProperty;
import com.midokura.midolman.mgmt.jaxrs.validation.annotation.IsUniqueRouterName;
import com.midokura.midolman.state.StateAccessException;

public class RouterNameConstraintValidator implements
        ConstraintValidator<IsUniqueRouterName, Router> {

    private final RouterDao dao;

    public RouterNameConstraintValidator(RouterDao dao) {
        this.dao = dao;
    }

    @Override
    public void initialize(IsUniqueRouterName constraintAnnotation) {
    }

    @Override
    public boolean isValid(Router value, ConstraintValidatorContext context) {

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                MessageProperty.IS_UNIQUE_ROUTER_NAME).addNode("name")
                .addConstraintViolation();

        Router router = null;
        String tenantId = value.getTenantId();
        if (tenantId == null && value.getId() != null) {
            // Need to get the tenant ID
            try {
                router = dao.get(value.getId());
            } catch (StateAccessException e) {
                throw new RuntimeException(
                        "State access exception occurred in validation");
            }
            tenantId = router.getTenantId();
        }

        try {
            router = dao.get(tenantId, value.getName());
        } catch (StateAccessException e) {
            throw new RuntimeException(
                    "State access exception occurred in validation");
        }

        // It's valid if the duplicate named router does not exist, or
        // exists but it's the same router.
        return (router == null || router.getId().equals(value.getId()));
    }
}
