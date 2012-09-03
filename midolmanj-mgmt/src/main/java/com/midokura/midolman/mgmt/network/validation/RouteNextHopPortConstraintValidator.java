/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.network.validation;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

import com.midokura.midolman.mgmt.network.Route;
import com.midokura.midolman.mgmt.validation.MessageProperty;

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
