/*
 * Copyright 2011 Midokura Europe SARL
 */

package org.midonet.functional_test.vm.libvirt.builders;

/**
 * Author: Toader Mihai Claudiu <mtoader@gmail.com>
 * <p/>
 * Date: 11/30/11
 * Time: 12:30 PM
 */
public abstract class AbstractBuilder<Builder extends AbstractBuilder<Builder, Target>, Target>
{
    protected abstract Builder self();

    public abstract Target build();
}
