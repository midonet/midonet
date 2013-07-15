/*
 * Copyright 2011 Midokura KK
 */

package org.midonet.midolman.rules;

import java.nio.ByteBuffer;
import java.util.UUID;

import org.midonet.midolman.rules.RuleResult.Action;
import org.midonet.packets.ICMP;
import org.midonet.packets.IPAddr;
import org.midonet.packets.IPv4;
import org.midonet.packets.MalformedPacketException;
import org.midonet.packets.TCP;
import org.midonet.packets.UDP;
import org.midonet.sdn.flows.WildcardMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class NatRule extends Rule {

    protected static final int USHORT = 0xffff;
    private static final Logger log = LoggerFactory.getLogger(NatRule.class);
    // The NatMapping is irrelevant to the hashCode, equals and serialization.
    public boolean dnat;

    public NatRule(Condition condition, Action action, boolean dnat) {
        super(condition, action);
        this.dnat = dnat;
        if (!action.equals(Action.ACCEPT) && !action.equals(Action.CONTINUE)
                && !action.equals(Action.RETURN))
            throw new IllegalArgumentException("A nat rule's action "
                    + "must be one of: ACCEPT, CONTINUE, or RETURN.");
    }

    // Default constructor for the Jackson deserialization.
    public NatRule() { super(); }

    public NatRule(Condition condition, Action action, UUID chainId,
            int position, boolean dnat) {
        super(condition, action, chainId, position);
        this.dnat = dnat;
        if (!action.equals(Action.ACCEPT) && !action.equals(Action.CONTINUE)
                && !action.equals(Action.RETURN))
            throw new IllegalArgumentException("A nat rule's action "
                    + "must be one of: ACCEPT, CONTINUE, or RETURN.");
    }

    @Override
    public int hashCode() {
        return super.hashCode() * 11 + (dnat ? 1231 : 1237);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof NatRule))
            return false;
        if (!super.equals(other))
            return false;
        return dnat == ((NatRule) other).dnat;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(super.toString());
        sb.append(", dnat=").append(dnat);
        return sb.toString();
    }

    /**
     * <p>Tells if NAT rules can be applied on the given match. Currently it
     * supports only UDP, TCP, and ICMP echo req/reply.</p>
     *
     * @param match being used to apply the rule
     * @return whether NAT rules are supported or not
     */
    protected boolean isNatSupported(WildcardMatch match) {
        byte nwProto = match.getNetworkProtocol();
        boolean supported = (nwProto == UDP.PROTOCOL_NUMBER) ||
                            (nwProto == TCP.PROTOCOL_NUMBER);
        if (nwProto == ICMP.PROTOCOL_NUMBER) {
                int type = match.getTransportSource();
                switch (type) {
                    case ICMP.TYPE_ECHO_REPLY:
                    case ICMP.TYPE_ECHO_REQUEST:
                        supported = (match.getIcmpIdentifier() != null);
                        break;
                    case ICMP.TYPE_PARAMETER_PROBLEM:
                    case ICMP.TYPE_TIME_EXCEEDED:
                    case ICMP.TYPE_UNREACH:
                        supported = (null != match.getIcmpData());
                        break;
                    default:
                        supported = false;
                }
            if (!supported) {
                log.debug("ICMP message not supported in NAT rules {}", match);
            }
        }
        return supported;
    }

    /**
     * Returns a NatLookupTpPair that contains the appropriate src and dst
     * to the given match. Applies various rules based on the type of packet
     * being dealt with.
     *
     * This is required because in ICMP error messages replying to previous
     * TCP/UDP or ICMP messages, the ports used to create the fwd mappings
     * are not present in the ICMP error msg itself, but hidden inside the
     * ICMP error data (that contains the original packet being replied to)
     *
     * @param match
     * @return the values that should be used for the nat lookup
     */
    protected NatLookupTuple getTpForMappingLookup(WildcardMatch match)
                                            throws MalformedPacketException {

        byte proto = match.getNetworkProtocol();
        IPAddr nwSrc = match.getNetworkSourceIP();
        int tpSrc = match.getTransportSource();
        IPAddr nwDst = match.getNetworkDestinationIP();
        int tpDst = match.getTransportDestination();
        if (match.getNetworkProtocol() == ICMP.PROTOCOL_NUMBER) {
            int icmpType = match.getTransportSource();
            switch (icmpType) {
                case ICMP.TYPE_ECHO_REPLY:
                case ICMP.TYPE_ECHO_REQUEST:
                    tpDst = tpSrc = match.getIcmpIdentifier() & USHORT;
                    break;
                case ICMP.TYPE_PARAMETER_PROBLEM:
                case ICMP.TYPE_UNREACH:
                case ICMP.TYPE_TIME_EXCEEDED:
                    // The nat mapping lookup should be done based on the
                    // contents of the ICMP data field
                    byte[] icmpData = match.getIcmpData();
                    ByteBuffer bb = ByteBuffer.wrap(icmpData);
                    IPv4 ipv4 = new IPv4();
                    ipv4.deserializeHeader(bb);
                    proto = ipv4.getProtocol();
                    if (proto == ICMP.PROTOCOL_NUMBER) {
                        // If replying to a prev. ICMP, mapping was done
                        // against the icmp id
                        ICMP icmp = new ICMP();
                        icmp.deserialize(bb);
                        tpSrc = tpDst = icmp.getIdentifier();
                    } else {
                        // Invert src and dst because the icmpData contains the
                        // original msg that the ICMP ERROR replies to
                        // TCP/UDP deserialize would likely fail since ICMP data
                        // doesn't contain the full datagram
                        ByteBuffer packet = bb.slice();
                        tpDst = TCP.getSourcePort(packet);
                        tpSrc = TCP.getDestinationPort(packet);
                    }
                    break;
                default:
                    tpSrc = tpDst = 0;

            }
        }
        return new NatLookupTuple(proto, nwSrc, tpSrc, nwDst, tpDst);
    }

    /**
     * Conveniency class to indicate what values should be used to query for
     * NAT mappings, since rules vary based on the packet type and its payload
     */
    class NatLookupTuple {

        final byte proto;
        final int tpSrc;
        final int tpDst;
        final IPAddr nwSrc;
        final IPAddr nwDst;

        NatLookupTuple(byte proto, IPAddr nwSrc, int tpSrc, IPAddr nwDst,
                       int tpDst) {
            this.proto = proto;
            this.nwSrc = nwSrc;
            this.tpSrc = tpSrc;
            this.nwDst = nwDst;
            this.tpDst = tpDst;
        }
    }

}
