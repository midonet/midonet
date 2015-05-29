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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomOneOf;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.midolman.rules.RuleResult.Action;
import org.midonet.midolman.simulation.PacketContext;

@ZoomOneOf(name = "redir_rule_data")
public class RedirectRule extends Rule {

    private final static Logger log = LoggerFactory.getLogger(RedirectRule.class);
    private static final long serialVersionUID = -7212783590950701193L;
    @ZoomField(name = "target_port", converter = UUIDUtil.Converter.class)
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
    public void apply(PacketContext pktCtx, RuleResult res, UUID ownerId) {
        res.action = Action.REDIRECT;
        res.redirectPort = targetPort;
        res.redirectIngress = ingress;
        res.redirectFailOpen = failOpen;
        log.debug("Redirecting flow {} port with ID {}.",
                  (ingress ? "IN" : "OUT"), targetPort);
    }

    @Override
    public int hashCode() {
        return Objects.hash(action, targetPort);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RedirectRule)) return false;
        if (!super.equals(other)) return false;

        RedirectRule res = (RedirectRule)other;
        if (!Objects.equals(targetPort, res.targetPort)) return false;
        if (ingress != res.ingress) return false;
        if (ingress != res.ingress) return false;
        return true;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("RedirectRule [");
        sb.append(super.toString());
        sb.append(", targetPort=").append(targetPort);
        sb.append(", ingressing=").append(ingress);
        sb.append(", failOpen=").append(failOpen);
        sb.append("]");
        return sb.toString();
    }
}
