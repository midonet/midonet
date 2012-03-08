/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.agent.command;

public class CommandValidatorFactory {

    public static TypeValidator getValidator(String property) {
        // Get the required type
        Class type = PropertyExecutor.get(property).getType();
        TypeValidator validator = null;
        if (type == Integer.class) {
            validator = new TypeValidator.IntTypeValidator();
        }
        // TODO(rossella) complete the class
        return validator;
    }
}
