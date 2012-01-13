/*
 * @(#)TestPathHelper        1.6 11/12/20
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.path;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

import junit.framework.Assert;

import org.junit.Test;

public class TestPathHelper {

    @Test
    public void testGetSubPathsGoodInputsWithoutRoot() throws Exception {

        String path = "/";
        List<String> paths = PathHelper.getSubPaths(path);
        Assert.assertEquals(0, paths.size());

        path = "/foo";
        paths = PathHelper.getSubPaths(path);
        Assert.assertEquals(1, paths.size());
        Assert.assertEquals("/foo", paths.get(0));

        path = "/foo/"; // Trailing empty space is removed.
        paths = PathHelper.getSubPaths(path);
        Assert.assertEquals(1, paths.size());
        Assert.assertEquals("/foo", paths.get(0));

        path = "/foo/bar/baz";
        paths = PathHelper.getSubPaths(path);

        Assert.assertEquals(3, paths.size());
        Assert.assertEquals("/foo", paths.get(0));
        Assert.assertEquals("/foo/bar", paths.get(1));
        Assert.assertEquals("/foo/bar/baz", paths.get(2));

        path = "//"; // Trailing empty spaces are removed
        paths = PathHelper.getSubPaths(path);
        Assert.assertEquals(0, paths.size());
    }

    @Test
    public void testGetSubPathsGoodInputsWithRoot() throws Exception {

        String path = "/";
        List<String> paths = PathHelper.getSubPaths(path, true);
        Assert.assertEquals(1, paths.size());
        Assert.assertEquals("/", paths.get(0));

        path = "/foo";
        paths = PathHelper.getSubPaths(path, true);
        Assert.assertEquals(2, paths.size());
        Assert.assertEquals("/", paths.get(0));
        Assert.assertEquals("/foo", paths.get(1));

        path = "/foo/"; // Trailing empty space is removed.
        paths = PathHelper.getSubPaths(path, true);
        Assert.assertEquals(2, paths.size());
        Assert.assertEquals("/", paths.get(0));
        Assert.assertEquals("/foo", paths.get(1));

        path = "/foo/bar/baz";
        paths = PathHelper.getSubPaths(path, true);

        Assert.assertEquals(4, paths.size());
        Assert.assertEquals("/", paths.get(0));
        Assert.assertEquals("/foo", paths.get(1));
        Assert.assertEquals("/foo/bar", paths.get(2));
        Assert.assertEquals("/foo/bar/baz", paths.get(3));

        path = "//"; // Trailing empty spaces are removed
        paths = PathHelper.getSubPaths(path, true);
        Assert.assertEquals(1, paths.size());
        Assert.assertEquals("/", paths.get(0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetSubPathsNullInput() throws Exception {
        PathHelper.getSubPaths(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetSubPathsEmptyInput() throws Exception {
        PathHelper.getSubPaths("  ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetSubPathsBadPathInput() throws Exception {
        PathHelper.getSubPaths("foo/bar/baz");
    }

    @Test(expected = NoSuchMethodException.class)
    public void testNoInstantiation() throws SecurityException,
            NoSuchMethodException, IllegalArgumentException,
            InstantiationException, IllegalAccessException,
            InvocationTargetException {
        Constructor<PathBuilder> constructor = PathBuilder.class
                .getDeclaredConstructor();
        constructor.newInstance();
    }
}
