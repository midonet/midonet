// Copyright 2013 Midokura Inc.

package org.midonet.util.throttling;

import java.util.Collection;

public interface ThrottlingGuardFactory {

    ThrottlingGuard build(String name);

    <E> ThrottlingGuard buildForCollection(String name, Collection<E> col);
}
