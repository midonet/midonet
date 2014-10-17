/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
