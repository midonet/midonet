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

package org.midonet.midolman.rules;

import java.util.UUID;

import com.google.common.base.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomOneOf;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.midolman.simulation.PacketContext;

@ZoomOneOf(name = "mirror_rule_data")
public class MirrorRule extends Rule {
    private static final Logger log = LoggerFactory.getLogger(MirrorRule.class);

    @ZoomField(name = "dst_port_id", converter = UUIDUtil.Converter.class)
    private UUID dstPortId;

    // Default constructor for the Jackson deserialization.
    // This constructor is also used by ZoomConvert.
    public MirrorRule() {
        super();
    }

    public MirrorRule(Condition condition, UUID portId) {
        super(condition, null);
        this.dstPortId = portId;
    }

    public MirrorRule(Condition condition, UUID portId, UUID chainId,
                      int position) {
        super(condition, null, chainId, position);
        this.dstPortId = portId;
    }

    public UUID getDstPortId() {
        return dstPortId;
    }

    public void setDstPortId(UUID portId) {
        this.dstPortId = portId;
    }

    @Override
    protected void apply(PacketContext pktCtx, RuleResult res, UUID ownerId) {
        res.action = RuleResult.Action.CONTINUE;
    }

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

        MirrorRule that = (MirrorRule) other;

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
