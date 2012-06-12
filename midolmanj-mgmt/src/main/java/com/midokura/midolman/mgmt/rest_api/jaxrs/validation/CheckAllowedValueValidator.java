/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.jaxrs.validation;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

import scala.actors.threadpool.Arrays;

public class CheckAllowedValueValidator implements
        ConstraintValidator<AllowedValue, String> {

    private String[] allowedValues;

    @Override
    public void initialize(AllowedValue annotation) {
        allowedValues = annotation.values();
    }

    @Override
    public boolean isValid(String object,
            ConstraintValidatorContext constraintContext) {
        if (object == null) {
            return true;
        }

        return Arrays.asList(allowedValues).contains(object);
    }
}
