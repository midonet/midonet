/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.util.collection;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.midonet.data.ListProvider;
import org.testng.Assert;

import java.util.List;

@RunWith(JUnitParamsRunner.class)
public class TestListUtil {

    @Test
    @Parameters(source = ListProvider.class, method="intListForToStringTest")
    public void testToString(List<Integer> input, String expected) {

        Assert.assertEquals(ListUtil.toString(input), expected);
    }

}
