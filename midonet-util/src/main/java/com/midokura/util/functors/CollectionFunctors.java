/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.functors;

import java.util.Collection;

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
}
