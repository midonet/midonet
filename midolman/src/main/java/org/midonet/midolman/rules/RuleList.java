// Copyright (c) 2013 Midokura SARL, All Rights Reserved.

package org.midonet.midolman.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class RuleList {

    public List<UUID> ruleList;

    // Default constructor for the Jackson deserialization.
    public RuleList() {
        super();
        this.ruleList = new ArrayList<UUID>();
    }

    public RuleList(List<UUID> ruleList_) {
        this.ruleList = ruleList_;
    }

    /* Custom accessors for Jackson serialization with more readable IPs. */

    public List<UUID> getRuleList() {
        return this.ruleList;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof RuleList))
            return false;

        RuleList rl = (RuleList) other;
        return rl.equals(this.ruleList);
    }

    @Override
    public int hashCode() {
        return ruleList.hashCode();
    }

    @Override
    public String toString() {
        return ruleList.toString();
    }

    public RuleList fromString() {
        return new RuleList();
    }

}
