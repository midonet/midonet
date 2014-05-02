/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.util.collection;

import java.util.List;
import java.util.Arrays;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import static junitparams.JUnitParamsRunner.$;

@RunWith(JUnitParamsRunner.class)
public class TestListUtil {

    @Test
    @Parameters(source = TestListUtil.class, method="intListForToStringTest")
    public void testToString(List<Integer> input, String expected) {

        Assert.assertEquals(ListUtil.toString(input), expected);
    }

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
