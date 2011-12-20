/*
 * @(#)PathHelper        1.6 20/12/11
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang.StringUtils;

/**
 * Path utility class.
 *
 * @version 1.6 20 Dec 2011
 * @author Ryu Ishimoto
 */
public class PathHelper {

    /**
     * Given a path, this method gets all the sub paths. The input must start
     * with '/', and is always trimmed. The return list always contains a root
     * path('/') as its first element. The path is split using the String
     * library's split method, which removes the trailing empty spaces. The
     * returned list is unmodifiable.
     *
     * @param path
     *            Path to parse.
     * @param includeRoot
     *            Include the root path, "/" in the output.
     * @return A collection of subpaths
     */
    public static List<String> getSubPaths(String path, boolean includeRoot) {

        if (path == null) {
            throw new IllegalArgumentException("path cannot be null");
        }

        path = path.trim();
        if (path.length() == 0) {
            throw new IllegalArgumentException("path cannot not be empty");
        }

        if (path.charAt(0) != '/') {
            throw new IllegalArgumentException("path must start with '/'");
        }

        String[] elems = path.split("/");
        List<String> paths = new ArrayList<String>(elems.length + 1);
        if (includeRoot) {
            paths.add("/");
        }
        if (elems.length > 0) {
            String p = StringUtils.EMPTY;
            for (int i = 1; i < elems.length; i++) {
                p = p + "/" + elems[i];
                paths.add(p);
            }
        }

        return Collections.unmodifiableList(paths);
    }

    /**
     * Given a path, this method gets all the sub paths. The input must start
     * with '/', and is always trimmed. The return list always contains a root
     * path('/') as its first element. The path is split using the String
     * library's split method, which removes the trailing empty spaces. The
     * returned list is unmodifiable.
     *
     * @param path
     *            Path to parse.
     * @return A collection of subpaths
     */
    public static List<String> getSubPaths(String path) {
        // By default, don't include the root path
        return getSubPaths(path, false);
    }

}
