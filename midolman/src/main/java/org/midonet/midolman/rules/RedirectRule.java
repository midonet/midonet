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

import java.util.Objects;
import java.util.UUID;

import com.google.protobuf.Message;

import org.midonet.cluster.data.ZoomField;
import org.midonet.midolman.simulation.PacketContext;

public class RedirectRule extends L2TransformRule {
    private static final long serialVersionUID = -7212783590950701194L;
    @ZoomField(name = "target_port_id")
    public UUID targetPort;
    @ZoomField(name = "ingress")
    public boolean ingress;
    @ZoomField(name = "fail_open")
    public boolean failOpen;

    // Default constructor for the Jackson deserialization.
    // This constructor is also needed by ZoomConvert.
    public RedirectRule() {
        super();
    }

    @Override
    public void afterFromProto(Message proto) {
        super.afterFromProto(proto);
        result = RuleResult.redirectResult(targetPort, ingress);
    }

    @Override
    public boolean apply(PacketContext pktCtx) {
        super.apply(pktCtx);
        if (ingress) {
            pktCtx.jlog().debug("Redirecting flow IN port with ID {}.",
                                targetPort);
        } else {
            pktCtx.jlog().debug("Redirecting flow OUT port with ID {}.",
                                targetPort);
            pktCtx.redirectOut(failOpen);
        }
        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(action, targetPort, ingress, failOpen);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RedirectRule)) return false;
        if (!super.equals(other)) return false;

        RedirectRule res = (RedirectRule)other;
        return Objects.equals(targetPort, res.targetPort)
            && (ingress != res.ingress)
            && (failOpen != res.failOpen);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("RedirectRule [");
        sb.append(super.toString());
        sb.append(", targetPort=").append(targetPort);
        sb.append(", ingressing=").append(ingress);
        sb.append(", failOpen=").append(failOpen);
        sb.append(", popVlan=").append(popVlan);
        sb.append(", pushVlan=").append(pushVlan);
        sb.append("]");
        return sb.toString();
    }
}
