/*
 * Copyright 2015 Midokura SARL
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

public class ArrayListUtil {

    public static <T> void resetWith(ArrayList<T> from, ArrayList<T> to) {
        to.clear();
        int size = from.size();
        for (int i = 0; i < size; ++i)
            to.add(from.get(i));
    }

    public static <T> boolean equals(ArrayList<T> l1, ArrayList<T> l2) {
        int size = l1.size();
        if (l2.size() != size)
            return false;
        for (int i = 0; i < size; ++i) {
            T o1 = l1.get(i);
            T o2 = l2.get(i);
            if (!(o1 == null ? o2 == null : o1.equals(o2)))
                return false;
        }
        return true;
    }
}
