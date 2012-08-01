/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.functors;

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
}
