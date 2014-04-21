/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.data;

import java.util.Arrays;

import static junitparams.JUnitParamsRunner.$;

/**
 * Provides data for List tests
 */
public class ListProvider {

    public static Object[] intListForToStringTest() {
        return $(
                $(null, "null"),
                $(Arrays.asList(), "[]"),
                $(Arrays.asList(0), "[0]"),
                $(Arrays.asList(0, null), "[0, null]"),
                $(Arrays.asList(0, 1), "[0, 1]")
        );
    }

}
