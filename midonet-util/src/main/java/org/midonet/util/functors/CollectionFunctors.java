/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.util.functors;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * // TODO: Explain yourself.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 4/5/12
 */
public class CollectionFunctors {
    public static <
        From, To,
        Source extends Collection<From>,
        Target extends Collection<To>
    > Target map(Source source, Functor<From, To> functor, Target target) {
        target.clear();

        for (From from : source) {
            To adaptedItem = functor.apply(from);
            if (adaptedItem != null) {
                target.add(adaptedItem);
            }
        }

        return target;
    }

    public static final Functor<Set<String>, Set<UUID>> strSetToUUIDSet =
            new Functor<Set<String>, Set<UUID>>() {
                @Override
                public Set<UUID> apply(Set<String> arg0) {
                    return CollectionFunctors.map(
                            arg0, Functors.strToUUID, new HashSet<UUID>());
                }
            };
}
