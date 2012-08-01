/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura;

/**
 * // TODO: mtoader ! Please explain yourself.
 */
public class Test<T extends Test<T>> {
    public static class ConcreteTest extends Test<ConcreteTest> {

    }
}
