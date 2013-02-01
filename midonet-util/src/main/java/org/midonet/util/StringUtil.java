/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.util;

import java.util.Iterator;

/**
 * Helper class for String operations. Only static methods should exist.
 */
public class StringUtil {

    public final static String EMPTY_STRING = "";
    public final static String IP_ADDRESS_REGEX_PATTERN =
            "^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}"
                    + "([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$";

    /**
     * Joins the elements of the provided array into a single String containing
     * the provided list of elements separated by the provided separator.
     *
     * @param list
     *            List of T elements to concatenate as strings.
     * @param separator
     *            Separator character.
     * @return Concatenated string.
     */
    public static <T> String join(T[] list, char separator) {

        if (list == null) {
            throw new IllegalArgumentException("list cannot be null.");
        }

        if (list.length == 0) {
            return EMPTY_STRING;
        }

        // Using 16 chars(default StringBuilder initial capacity) as the
        // expected string size for each item.
        StringBuilder sb = new StringBuilder(list.length * 16);

        for (T obj : list) {
            sb.append(obj).append(separator);
        }

        // Remove the last separator char.
        return sb.deleteCharAt(sb.length() - 1).toString();
    }

    /**
     * Join the elements of the provided Iterable into a single string using the
     * separator provided.
     */
    public static <T> String join(Iterable<T> items, char separator) {
        if (items == null) {
            throw new IllegalArgumentException("list cannot be null");
        }

        StringBuilder builder = new StringBuilder();
        for (Iterator<T> iterator = items.iterator(); iterator.hasNext();) {
            T item = iterator.next();
            builder.append(item);
            if (iterator.hasNext()) {
                builder.append(separator);
            }
        }

        return builder.toString();
    }

    /**
     * Checks whether the string is null or empty.
     *
     * @param str
     *            String to check.
     * @return True if the string is either null or empty.
     */
    public static boolean isNullOrEmpty(String str) {
        return (str == null || str.equals(""));
    }

    private StringUtil() {
    }
}
