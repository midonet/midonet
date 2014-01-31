/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.util.functors;

/**
 * Simple Unary functor that converts an Argument into a Return value.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 4/5/12
 */
public interface Functor<Argument0, Return> {
    public Return apply(Argument0 arg0);
}
