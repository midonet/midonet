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
