/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.util.functors;

/**
 * Simple interface to abstract a function that doesn't return anything.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 5/3/12
 */
public interface UnaryFunctor<Argument> {
    public void apply(Argument arg0);
}
