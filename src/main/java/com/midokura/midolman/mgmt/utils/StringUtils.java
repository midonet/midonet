/*
 * @(#)StringUtils        1.6 11/11/11
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.utils;

import java.util.ArrayList;
import java.util.List;

public class StringUtils {

    public static List<String> splitAndAppend(String path, String regex) {
        String[] pathElems = path.split(regex);
        List<String> paths = new ArrayList<String>();
        String currentPath = "";
        for (String pathElem : pathElems) {
            if (!pathElem.equals("")) {
                currentPath = currentPath + regex + pathElem;
                paths.add(currentPath);
            }
        }
        return paths;
    }

}
