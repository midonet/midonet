package com.midokura.midolman.rules;

import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.layer4.NwTpPair;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.rules.RuleResult.Action;

public class ForwardNatRule extends NatRule {
    protected transient Set<NatTarget> targets;
    private transient boolean floatingIp;
    private transient int floatingIpAddr;
    private final static Logger log = LoggerFactory
            .getLogger(ForwardNatRule.class);

    public ForwardNatRule(Condition condition, Set<NatTarget> targets,
            Action action, boolean dnat) {
        super(condition, action, dnat);
        this.targets = targets;
        if (null == targets || targets.size() == 0)
            throw new IllegalArgumentException(
                    "A forward nat rule must have targets.");
        floatingIp = false;
        if (targets.size() == 1) {
            NatTarget tg = targets.iterator().next();
            if (tg.nwStart == tg.nwEnd && 0 == tg.tpStart && 0 == tg.tpStart) {
                floatingIp = true;
                floatingIpAddr = tg.nwStart;
            }
        }
    }

    // Default constructor for the Jackson deserialization.
    public ForwardNatRule() {
        super();
    }

    public ForwardNatRule(Condition condition, Action action, UUID chainId,
            int position, boolean dnat, Set<NatTarget> targets) {
        super(condition, action, chainId, position, dnat);
        this.targets = targets;
        if (null == targets || targets.size() == 0)
            throw new IllegalArgumentException(
                    "A forward nat rule must have targets.");
    }

    @Override
    public void apply(MidoMatch flowMatch, UUID inPortId, UUID outPortId,
            RuleResult res) {
        if (null == natMap)
            return;
        if (dnat)
            applyDnat(flowMatch, res);
        else
            applySnat(flowMatch, res);
    }

    public void applyDnat(MidoMatch flowMatch, RuleResult res) {
        if (floatingIp) {
            log.debug("DNAT mapping floating ip {} to internal ip {}",
                    res.match.getNetworkDestination(), floatingIpAddr);
            res.match.setNetworkDestination(floatingIpAddr);
            res.action = action;
            return;
        }
        NwTpPair conn = natMap.lookupDnatFwd(res.match.getNetworkSource(),
                res.match.getTransportSource(), res.match
                        .getNetworkDestination(), res.match
                        .getTransportDestination());
        if (null == conn)
            conn = natMap.allocateDnat(res.match.getNetworkSource(), res.match
                    .getTransportSource(), res.match.getNetworkDestination(),
                    res.match.getTransportDestination(), targets, flowMatch);
        else
            log.debug("Found existing forward DNAT {}:{} for flow from {}:{} "
                    + "to {}:{}", new Object[] {
                    IPv4.fromIPv4Address(conn.nwAddr), conn.tpPort,
                    IPv4.fromIPv4Address(res.match.getNetworkSource()),
                    res.match.getTransportSource(),
                    IPv4.fromIPv4Address(res.match.getNetworkDestination()),
                    res.match.getTransportDestination() });
        // TODO(pino): deal with case that conn couldn't be allocated.
        res.match.setNetworkDestination(conn.nwAddr);
        res.match.setTransportDestination(conn.tpPort);
        res.action = action;
        res.trackConnection = true;
    }

    public void applySnat(MidoMatch flowMatch, RuleResult res) {
        if (floatingIp) {
            log.debug("SNAT mapping internal ip {} to floating ip {}",
                    res.match.getNetworkSource(), floatingIpAddr);
            res.match.setNetworkSource(floatingIpAddr);
            res.action = action;
            return;
        }
        NwTpPair conn = natMap.lookupSnatFwd(res.match.getNetworkSource(),
                res.match.getTransportSource(), res.match
                        .getNetworkDestination(), res.match
                        .getTransportDestination());
        if (null == conn)
            conn = natMap.allocateSnat(res.match.getNetworkSource(), res.match
                    .getTransportSource(), res.match.getNetworkDestination(),
                    res.match.getTransportDestination(), targets, flowMatch);
        else 
            log.debug("Found existing forward SNAT {}:{} for flow from {}:{} "
                    + "to {}:{}", new Object[] {
                    IPv4.fromIPv4Address(conn.nwAddr), conn.tpPort,
                    IPv4.fromIPv4Address(res.match.getNetworkSource()),
                    res.match.getTransportSource(),
                    IPv4.fromIPv4Address(res.match.getNetworkDestination()),
                    res.match.getTransportDestination() });
        // TODO(pino): deal with case that conn couldn't be allocated.
        res.match.setNetworkSource(conn.nwAddr);
        res.match.setTransportSource(conn.tpPort);
        res.action = action;
        res.trackConnection = true;
    }

    // Used by RuleEngine to discover resources that must be initialized
    // or preserved. Not all NatRules have NatTargets (e.g. reverse nats).
    public Set<NatTarget> getNatTargets() {
        return targets;
    }

    // Setter for the JSON serialization.
    public void setNatTargets(Set<NatTarget> targets) {
        this.targets = targets;
    }

    @Override
    public int hashCode() {
        int hash = super.hashCode();
        return 29 * hash + targets.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof ForwardNatRule))
            return false;
        if (!super.equals(other))
            return false;
        ForwardNatRule r = (ForwardNatRule) other;
        return targets.equals(r.targets);
    }
}
