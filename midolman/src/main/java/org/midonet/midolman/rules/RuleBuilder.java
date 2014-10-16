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

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.midonet.cluster.data.neutron.RuleProtocol;
import org.midonet.cluster.data.neutron.SecurityGroupRule;
import org.midonet.packets.ARP;
import org.midonet.packets.IPSubnet;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.MAC;


public class RuleBuilder {

    private UUID chainId;
    private Rule r;
    private Condition c;

    public RuleBuilder(UUID chainId) {
        this.chainId = chainId;
        this.c = new Condition();
    }

    public Rule drop() {
        r = new LiteralRule(c, RuleResult.Action.DROP);
        r.chainId = chainId;
        return r;
    }

    public Rule accept() {
        r = new LiteralRule(c, RuleResult.Action.ACCEPT);
        r.chainId = chainId;
        return r;
    }

    public Rule sourceNat(NatTarget nt) {
        Set<NatTarget> targets = new HashSet<>();
        targets.add(nt);
        r = new ForwardNatRule(c, RuleResult.Action.ACCEPT, chainId, 1, false,
            targets);
        return r;
    }

    public Rule reverseSourceNat() {
        r = new ReverseNatRule(c, RuleResult.Action.ACCEPT, false);
        r.chainId = chainId;
        return r;
    }

    public Rule destNat(NatTarget nt) {
        Set<NatTarget> targets = new HashSet<>();
        targets.add(nt);
        r = new ForwardNatRule(c, RuleResult.Action.ACCEPT, chainId, 1, true,
            targets);
        return r;
    }

    public RuleBuilder fromIp(IPv4Addr addr) {
        return fromSubnet(addr.subnet(32));
    }

    public RuleBuilder fromSubnet(IPSubnet<?> addr) {
        c.nwSrcIp = addr;
        return this;
    }

    public RuleBuilder toIp(IPv4Addr addr) {
        return toSubnet(addr.subnet(32));
    }

    public RuleBuilder toSubnet(IPSubnet<?> addr) {
        c.nwDstIp = addr;
        return this;
    }

    public RuleBuilder comingInPort(UUID portId) {
        if (c.inPortIds == null) {
            c.inPortIds = new HashSet<>();
        }
        c.inPortIds.add(portId);
        return this;
    }

    public RuleBuilder goingOutPort(UUID portId) {
        if (c.outPortIds == null) {
            c.outPortIds = new HashSet<>();
        }
        c.outPortIds.add(portId);
        return this;
    }

    public RuleBuilder hasDestIp(IPv4Addr ip) {
        c.nwDstIp = new IPv4Subnet(ip, 32);
        return this;
    }

    public RuleBuilder hasSrcIp(IPv4Addr ip) {
        c.nwSrcIp = new IPv4Subnet(ip, 32);
        return this;
    }

    public RuleBuilder isAnyFragmentState() {
        c.fragmentPolicy = FragmentPolicy.ANY;
        return this;
    }

    public RuleBuilder notICMP() {
        c.nwProtoInv = true;
        c.nwProto = RuleProtocol.ICMP.number();
        return this;
    }

    public RuleBuilder notARP() {
        c.etherType = (int) ARP.ETHERTYPE;
        c.invDlType = true;
        return this;
    }

    public RuleBuilder notFromMac(MAC macAddr) {
        c.ethSrc = macAddr;
        c.invDlSrc = true;
        return this;
    }

    public RuleBuilder notFromSubnet(IPSubnet sub) {
        c.nwSrcIp = sub;
        c.nwSrcInv = true;
        c.etherType = (int) sub.ethertype();
        return this;
    }

    public RuleBuilder isReturnFlow() {
        c.matchReturnFlow = true;
        return this;
    }

    public RuleBuilder securityGroupRule(SecurityGroupRule sgRule) {
        c.nwProto = sgRule.protocolNumber();
        c.etherType = sgRule.ethertype();
        c.matchForwardFlow = sgRule.isEgress();
        c.tpDst = sgRule.portRange();
        c.id = sgRule.id;

        if (sgRule.isIngress()) {
            c.nwSrcIp = sgRule.remoteIpv4Subnet();
            c.ipAddrGroupIdSrc = sgRule.remoteGroupId;
        } else {
            c.nwDstIp = sgRule.remoteIpv4Subnet();
            c.ipAddrGroupIdDst = sgRule.remoteGroupId;
        }
        return this;
    }
}
