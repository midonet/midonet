/*
 * @(#)CollectionUtils        1.6 11/11/11
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

public class CollectionUtils {

    public static <T> List<T> uniquifyAndSort(List<T>... lists) {
        SortedSet<T> set = new TreeSet<T>();
        for (List<T> list : lists) {
            set.addAll(list);
        }
        return new ArrayList<T>(set);
    }

}
