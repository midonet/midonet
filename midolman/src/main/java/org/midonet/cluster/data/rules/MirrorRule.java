/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.cluster.data.rules;

import java.util.UUID;

import com.google.common.base.Objects;

import org.midonet.cluster.data.Rule;
import org.midonet.midolman.rules.Condition;
import org.midonet.midolman.rules.RuleResult;

public class MirrorRule extends Rule<MirrorRule.Data, MirrorRule> {
    public MirrorRule(Condition condition) {
        this(condition, new Data());
    }

    public MirrorRule(Condition condition, Data data) {
        super(null, condition, data);
    }

    public UUID getDstPotId() {
        return getData().dstPortId;
    }

    public MirrorRule setPortId(UUID portId) {
        getData().dstPortId = portId;
        return self();
    }

    @Override
    protected MirrorRule self() {
        return this;
    }

    @Override
    public MirrorRule setAction(RuleResult.Action action) {
        throw new IllegalArgumentException(
                "Cannot set an action on mirror rule");
    }

    public static class Data extends Rule.Data {
        public UUID dstPortId;

        @Override
        public int hashCode() {
            return super.hashCode() * 31 + Objects.hashCode(dstPortId);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }

            if (other == null || getClass() != other.getClass() ||
                    super.equals(other)) {
                return false;
            }

            Data that = (Data) other;

            return Objects.equal(this.dstPortId, that.dstPortId);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("MirrorRule [");
            sb.append(super.toString());
            sb.append(", dstPortId=").append(dstPortId);
            sb.append("]");

            return sb.toString();
        }
    }
}
