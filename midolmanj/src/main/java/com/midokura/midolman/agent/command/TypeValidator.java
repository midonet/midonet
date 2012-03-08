/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.agent.command;

public abstract class TypeValidator<T> {

    public abstract T validateAndConvert(String property, String value);

    public static class IntTypeValidator extends TypeValidator<Integer> {
        @Override
        public Integer validateAndConvert(String property, String value) {
            Integer intValue;
            intValue = Integer.parseInt(value);
            return intValue;
        }
    }
    //TODO(rossella) complete validators
}

