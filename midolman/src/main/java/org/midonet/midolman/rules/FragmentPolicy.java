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
package org.midonet.midolman.rules;

import java.util.EnumSet;

import org.midonet.odp.flows.IPFragmentType;

import static org.midonet.odp.flows.IPFragmentType.*;

/**
 * Different packet-matching behaviors. Can be specified in a Rule's
 * Condition to cause rules to match some combination of unfragmented
 * packets, header fragments, and non-header fragments.
 */
public enum FragmentPolicy {
    /**
     * Matches any packet, fragmented or not.
     */
    ANY(First, Later, None),

    /**
     * Matches only non-header fragment packets.
     */
    NONHEADER(Later),

    /**
     * Matches unfragmented packets and header fragments, i.e., any
     * packet with full headers.
     */
    HEADER(First, None),

    /**
     * Matches only unfragmented packets.
     */
    UNFRAGMENTED(None);

    private final EnumSet<IPFragmentType> acceptedFragmentTypes;

    public static final String pattern = "any|nonheader|header|unfragmented";

    private FragmentPolicy(IPFragmentType... fragmentTypes) {
        acceptedFragmentTypes = EnumSet.noneOf(IPFragmentType.class);
        for (IPFragmentType fragmentType : fragmentTypes)
            acceptedFragmentTypes.add(fragmentType);
    }

    public boolean accepts(IPFragmentType fragmentType) {
        return acceptedFragmentTypes.contains(fragmentType);
    }
}
