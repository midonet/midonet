/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.util.collection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.common.base.Function;

/**
 * Class that contains utility methods for List
 */
public class ListUtil {

    /**
     * Convert to string a list of T objects.  This is the List version of
     * Arrays.toString.  Just like in Arrays.toString, all null objects are
     * converted to strings 'null', including the input argument.  Thus,
     * it is safe to call this method and pass in null as the argument.
     *
     * @param list List of objects to convert to string
     * @param <T> Type of objects stored in the List
     * @return String representation of the provided list
     */
    public static <T> String toString(List<T> list) {

        return list == null ? "null" : Arrays.toString(list.toArray());
    }

    /**
     * Returns a new list containing the elements from the given one that
     * satisfy p
     */
    public static <T> List<T> filter(List<T> list, Function<T, Boolean> p) {
        List<T> res = new ArrayList<>(list.size());
        for (T t : list) {
            if (p.apply(t))
                res.add(t);
        }
        return res;
    }

    /**
     * Returns a new list where each member is a convert from the old list according to p.
     */
    public static <T, NT> List<NT> map(List<T> list, Function<T, NT> p) {
        List<NT> res = new ArrayList<>(list.size());
        for (T t : list) {
            res.add(p.apply(t));
        }
        return res;
    }

}
