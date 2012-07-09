/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.jaxrs.validation.constraint;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

import com.midokura.midolman.mgmt.data.dto.Route;
import com.midokura.midolman.mgmt.jaxrs.validation.MessageProperty;
import com.midokura.midolman.mgmt.jaxrs.validation.annotation.NextHopPortNotNull;

public class RouteNextHopPortConstraintValidator implements
        ConstraintValidator<NextHopPortNotNull, Route> {

    @Override
    public void initialize(NextHopPortNotNull constraintAnnotation) {
    }

    @Override
    public boolean isValid(Route value, ConstraintValidatorContext context) {

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                MessageProperty.ROUTE_NEXT_HOP_PORT_NOT_NULL)
                .addNode("nextHopPort").addConstraintViolation();

        if (!value.isNormal()) {
            // This validation only applies for 'normal' route.
            return true;
        }

        return (value.getNextHopPort() != null);
    }
}
