/*
 * @(#)TestStringUtil        1.6 11/12/15
 *
 * Copyright 2011 Midokura KK
 */
package org.midonet.util;

import junit.framework.Assert;

import org.junit.Test;

public class TestStringUtil {

    @Test
    public void testJoinGoodInputs() throws Exception {

        // Empty list -> empty string
        String[] list = {};
        char separator = '/';
        String result = StringUtil.join(list, separator);
        Assert.assertEquals(StringUtil.EMPTY_STRING, result);

        // One item -> same item
        list = new String[] { "foo" };
        result = StringUtil.join(list, separator);
        Assert.assertEquals("foo", result);

        // One item of empty string -> same item
        list = new String[] { StringUtil.EMPTY_STRING };
        result = StringUtil.join(list, separator);
        Assert.assertEquals(StringUtil.EMPTY_STRING, result);

        // Multiple items
        list = new String[] { "foo", "bar", "baz" };
        result = StringUtil.join(list, separator);
        Assert.assertEquals("foo/bar/baz", result);

        // Multiple items of empty string
        list = new String[] { StringUtil.EMPTY_STRING, StringUtil.EMPTY_STRING,
                StringUtil.EMPTY_STRING };
        result = StringUtil.join(list, separator);
        Assert.assertEquals("//", result);

        // Mix of empty string and non-empty string
        list = new String[] { "foo", StringUtil.EMPTY_STRING, "bar",
                StringUtil.EMPTY_STRING };
        result = StringUtil.join(list, separator);
        Assert.assertEquals("foo//bar/", result);

        // Another mix that ends up creating a path-like string
        list = new String[] { StringUtil.EMPTY_STRING, "foo", "bar", "baz" };
        result = StringUtil.join(list, separator);
        Assert.assertEquals("/foo/bar/baz", result);

        // Test int
        Integer[] intList = new Integer[] { 1, 10, -100 };
        result = StringUtil.join(intList, separator);
        Assert.assertEquals("1/10/-100", result);

    }

    @Test(expected = IllegalArgumentException.class)
    public void testJoinNullInput() throws Exception {
        StringUtil.join((Object[]) null, '/');
    }

    @Test
    public void testNullOrEmpty() throws Exception {

        boolean result = StringUtil.isNullOrEmpty(null);
        Assert.assertTrue(result);

        result = StringUtil.isNullOrEmpty("");
        Assert.assertTrue(result);

        result = StringUtil.isNullOrEmpty("foo");
        Assert.assertFalse(result);

        result = StringUtil.isNullOrEmpty(" ");
        Assert.assertFalse(result);
    }

}
