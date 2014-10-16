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

package org.midonet;

import java.lang.reflect.Field;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;

import sun.misc.Unsafe;

@SuppressWarnings("unsafe")
public final class Util {

    private static final Unsafe THE_UNSAFE;
    static {
        try {
            final PrivilegedExceptionAction<Unsafe> action = new PrivilegedExceptionAction<Unsafe>() {
                public Unsafe run() throws Exception {
                    Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
                    theUnsafe.setAccessible(true);
                    return (Unsafe) theUnsafe.get(null);
                }
            };

            THE_UNSAFE = AccessController.doPrivileged(action);
        }
        catch (Exception e) {
            throw new RuntimeException("Unable to load unsafe", e);
        }
    }

    /**
     * Get a handle for the Unsafe instance, used for accessing
     * low-level concurrency and memory constructs.
     */
    public static Unsafe getUnsafe() {
        return THE_UNSAFE;
    }

    /**
     * Performs an unchecked cast and suppresses the warning. Don't use this
     * unless you have to and you're sure there's no typesafe alternative.
     *
     * Due to type inference, it is often not necessary to specify the target
     * type explicitly. For example, both casts below are valid.
     *
     * Object o = Arrays.asList("a", "b", "c");
     * List<Object> listRef1 = uncheckedCast(o);
     * List<Object> listRef2 = Util.<List<Object>>uncheckedCast(o);
     */
    @SuppressWarnings("unchecked")
    public static <T> T uncheckedCast(Object o) {
        return (T)o;
    }
}
