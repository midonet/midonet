/*
 * @(#)MatchTranslation.java
 *
 * Copyright 2012 Midokura KK
 */

package com.midokura.midolman.openflow;

import org.openflow.protocol.OFMatch;

import com.midokura.midolman.openflow.nxm.*;
import com.midokura.midolman.packets.ARP;
import com.midokura.midolman.packets.ICMP;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.packets.TCP;
import com.midokura.midolman.packets.UDP;
import com.midokura.midolman.util.Net;

public class MatchTranslation {

    public static NxMatch toNxMatch(OFMatch match, long matchingTunnelId) {
        /* The documentation for NXM states that:
         * "A zero-length nx_match (one with no "nxm_entry"s) matches every
         * packet. An nxm_entry places a constraint on the packets matched by
         * the nx_match"
         *
         * So in this method, we only need to create NxEntry instances for any
         * OFMatch fields that are not (completely) wild-carded.
         */
        try {
            NxMatch nxm = new NxMatch();
            // First, deal with inPort and tunnel ID
            if ((match.getWildcards() & OFMatch.OFPFW_IN_PORT) == 0)
                nxm.setInPort(match.getInputPort());
            if (matchingTunnelId != 0)
                nxm.setTunnelId(matchingTunnelId);

            // Now layer2
            if ((match.getWildcards() & OFMatch.OFPFW_DL_DST) == 0)
                nxm.setEthDst(match.getDataLayerDestination());
            if ((match.getWildcards() & OFMatch.OFPFW_DL_SRC) == 0)
                nxm.setEthSrc(match.getDataLayerSource());
            // TODO: deal with vlan ID and PCP
            if ((match.getWildcards() &
                    (OFMatch.OFPFW_DL_VLAN | OFMatch.OFPFW_DL_VLAN_PCP)) == 0) {
            }
            if ((match.getWildcards() & OFMatch.OFPFW_DL_TYPE) == 0) {
                short etherType = match.getDataLayerType();
                nxm.setEthType(etherType);

                // The rest of the translation depends on the EtherType.
                if (etherType == ARP.ETHERTYPE) {
                    toNxMatchARP(match, nxm);
                    return nxm;
                }
                else if (etherType == IPv4.ETHERTYPE) {
                    toNxMatchIPv4(match, nxm);
                    return nxm;
                }
            }
            // For any other EtherType don't allow setting higher layer fields.
            verifyNoL3(match);
            verifyNoL4(match);
            return nxm;
        }
        catch (NxmDuplicateEntryException e) {
            // This should never happen in this code since we don't try to set
            // the same field twice.
            throw new RuntimeException(e);
        }
        catch (NxmPrerequisiteException e) {
            // This should never happen since we make sure we make sure we
            // respect NxMatch dependencies.
            throw new RuntimeException(e);
        }
    }

    private static void verifyNoL3(OFMatch match) {
        int maskLen = match.getNetworkDestinationMaskLen();
        if (maskLen > 0)
            throw new RuntimeException("Cannot set nw_dst for this dl_type: " +
                    match.toString());
        maskLen = match.getNetworkSourceMaskLen();
        if (maskLen > 0)
            throw new RuntimeException("Cannot set nw_src for this dl_type: " +
                    match.toString());
        if ((match.getWildcards() & OFMatch.OFPFW_NW_TOS) == 0)
            throw new RuntimeException("Cannot set nw_tos for this dl_type: " +
                    match.toString());
        if ((match.getWildcards() & OFMatch.OFPFW_NW_PROTO) == 0)
            throw new RuntimeException("Cannot set nw_proto for this " +
                    "dl_type: " + match.toString());
    }

    private static void toNxMatchARP(OFMatch match, NxMatch nxm)
            throws NxmPrerequisiteException, NxmDuplicateEntryException {
        int maskLen = match.getNetworkDestinationMaskLen();
        if (maskLen > 0)
            nxm.setArpTPA(Net.convertIntToInetAddress(
                    match.getNetworkDestination()), maskLen);
        maskLen = match.getNetworkSourceMaskLen();
        if (maskLen > 0) {
            nxm.setArpSPA(Net.convertIntToInetAddress(
                    match.getNetworkSource()), maskLen);
        }
        if ((match.getWildcards() & OFMatch.OFPFW_NW_PROTO) == 0)
            nxm.setArpOp((short) (0xff & match.getNetworkProtocol()));
        if ((match.getWildcards() & OFMatch.OFPFW_NW_TOS) == 0)
            throw new RuntimeException("Cannot specify ip_tos when dl_type " +
                    "is ARP.");
        verifyNoL4(match);
    }

    private static void toNxMatchIPv4(OFMatch match, NxMatch nxm)
            throws NxmPrerequisiteException, NxmDuplicateEntryException {
        int maskLen = match.getNetworkDestinationMaskLen();
        if (maskLen > 0)
            nxm.setIpDst(Net.convertIntToInetAddress(
                    match.getNetworkDestination()), maskLen);
        maskLen = match.getNetworkSourceMaskLen();
        if (maskLen > 0) {
            nxm.setIpSrc(Net.convertIntToInetAddress(
                    match.getNetworkSource()), maskLen);
        }
        if ((match.getWildcards() & OFMatch.OFPFW_NW_TOS) == 0)
            nxm.setIpTos(match.getNetworkTypeOfService());
        if ((match.getWildcards() & OFMatch.OFPFW_NW_PROTO) == 0) {
            byte proto = match.getNetworkProtocol(); 
            nxm.setIpProto(proto);
            // The rest of the translation depends on the network protocol.
            if (proto == TCP.PROTOCOL_NUMBER) {
                toNxMatchTCP(match, nxm);
                return;
            }
            else if (proto == UDP.PROTOCOL_NUMBER) {
                toNxMatchUDP(match, nxm);
                return;
            }
            else if (proto == ICMP.PROTOCOL_NUMBER) {
                toNxMatchICMP(match, nxm);
                return;
            }
        }
        verifyNoL4(match);
    }

    private static void toNxMatchTCP(OFMatch match, NxMatch nxm)
            throws NxmPrerequisiteException, NxmDuplicateEntryException {
        if ((match.getWildcards() & OFMatch.OFPFW_TP_DST) == 0)
            nxm.setTcpDst(match.getTransportDestination());
        if ((match.getWildcards() & OFMatch.OFPFW_TP_SRC) == 0)
            nxm.setTcpSrc(match.getTransportSource());
    }

    private static void toNxMatchUDP(OFMatch match, NxMatch nxm)
            throws NxmPrerequisiteException, NxmDuplicateEntryException {
        if ((match.getWildcards() & OFMatch.OFPFW_TP_DST) == 0)
            nxm.setUdpDst(match.getTransportDestination());
        if ((match.getWildcards() & OFMatch.OFPFW_TP_SRC) == 0)
            nxm.setUdpSrc(match.getTransportSource());
    }

    private static void toNxMatchICMP(OFMatch match, NxMatch nxm)
            throws NxmPrerequisiteException, NxmDuplicateEntryException {
        if ((match.getWildcards() & OFMatch.OFPFW_TP_DST) == 0)
            nxm.setIcmpCode((byte)(0xff & match.getTransportDestination()));
        if ((match.getWildcards() & OFMatch.OFPFW_TP_SRC) == 0)
            nxm.setIcmpType((byte)(0xff & match.getTransportSource()));
    }

    private static void verifyNoL4(OFMatch match) {
        if ((match.getWildcards() & OFMatch.OFPFW_TP_DST) == 0)
            throw new RuntimeException("Cannot specify tp_dst for this " +
                    "match: " + match.toString());
        if ((match.getWildcards() & OFMatch.OFPFW_TP_SRC) == 0)
            throw new RuntimeException("Cannot specify tp_src for this " +
                    "match: " + match.toString());
    }

    public static OFMatch toOFMatch(NxMatch nxm) {
        MidoMatch match = new MidoMatch();
        {
            OfInPortNxmEntry e = nxm.getInPortEntry();
            if (null != e)
                match.setInputPort(e.getValue());
        }
        {
            OfEthDstNxmEntry e = nxm.getEthDstEntry();
            if (null != e)
                match.setDataLayerDestination(e.getAddress());
        }
        {
            OfEthSrcNxmEntry e = nxm.getEthSrcEntry();
            if (null != e)
                match.setDataLayerSource(e.getAddress());
        }
        {
            OfEthTypeNxmEntry e = nxm.getEthTypeEntry();
            if (null != e)
                match.setDataLayerType(e.getValue());
        }
        {
            OfArpOpNxmEntry e = nxm.getArpOpEntry();
            if (null != e)
                match.setNetworkProtocol((byte)e.getValue());
        }
        {
            OfArpSrcPacketNxmEntry e = nxm.getArpSPAEntry();
            if (null != e)
                match.setNetworkSource(e.getIntAddress(), e.getMaskLen());
        }
        {
            OfArpTargetPacketNxmEntry e = nxm.getArpTPAEntry();
            if (null != e)
                match.setNetworkDestination(e.getIntAddress(), e.getMaskLen());
        }
        {
            OfIpDstNxmEntry e = nxm.getIpDstEntry();
            if (null != e)
                match.setNetworkDestination(e.getIntAddress(), e.getMaskLen());
        }
        {
            OfIpSrcNxmEntry e = nxm.getIpSrcEntry();
            if (null != e)
                match.setNetworkSource(e.getIntAddress(), e.getMaskLen());
        }
        {
            OfIpProtoNxmEntry e = nxm.getIpProtoEntry();
            if (null != e)
                match.setNetworkProtocol(e.getValue());
        }
        {
            OfIpTosNxmEntry e = nxm.getIpTosEntry();
            if (null != e)
                match.setNetworkTypeOfService(e.getValue());
        }
        {
            OfTcpDstNxmEntry e = nxm.getTcpDstEntry();
            if (null != e)
                match.setTransportDestination(e.getValue());
        }
        {
            OfTcpSrcNxmEntry e = nxm.getTcpSrcEntry();
            if (null != e)
                match.setTransportSource(e.getValue());
        }
        {
            OfUdpDstNxmEntry e = nxm.getUdpDstEntry();
            if (null != e)
                match.setTransportDestination(e.getValue());
        }
        {
            OfUdpSrcNxmEntry e = nxm.getUdpSrcEntry();
            if (null != e)
                match.setTransportSource(e.getValue());
        }
        {
            OfIcmpCodeNxmEntry e = nxm.getIcmpCodeEntry();
            if (null != e)
                match.setTransportDestination((short)(0xff & e.getValue()));
        }
        {
            OfIcmpTypeNxmEntry e = nxm.getIcmpTypeEntry();
            if (null != e)
                match.setTransportSource((short)(0xff & e.getValue()));

        }
        return match;
    }
}
