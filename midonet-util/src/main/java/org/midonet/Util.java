/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
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
