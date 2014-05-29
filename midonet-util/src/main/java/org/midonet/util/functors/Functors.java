/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.util.functors;

import java.util.UUID;

/**
 * // TODO: mtoader ! Please explain yourself.
 */
public class Functors {
    public static <T> Functor<T, T> identity() {
        return new Functor<T, T>() {
            @Override
            public T apply(T arg0) {
                return arg0;
            }
        };
    }

    public static final Functor<String, UUID> strToUUID =
            new Functor<String, UUID>() {
                @Override
                public UUID apply(String arg0) {
                    try {
                        return UUID.fromString(arg0);
                    } catch (IllegalArgumentException ex) {
                        return null;
                    }
                }
            };
}
