/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
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
