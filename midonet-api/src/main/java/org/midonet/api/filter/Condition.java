/*
 * Copyright (c) 2013-2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.filter;

import java.util.*;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Pattern;

import org.midonet.api.UriResource;
import org.midonet.api.rest_api.BadRequestHttpException;
import org.midonet.api.validation.MessageProperty;
import org.midonet.midolman.rules.FragmentPolicy;
import org.midonet.odp.flows.IPFragmentType;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.MAC;
import org.midonet.util.Range;
import org.midonet.util.version.Since;

import static org.midonet.packets.Unsigned.unsign;
import static org.midonet.midolman.rules.Condition.NO_MASK;

/* Trace class */
public abstract class Condition extends UriResource {
    @Pattern(regexp = IPv4.regex,
             message = "is an invalid IP format")
    private String nwDstAddress;

    @Min(0)
    @Max(32)
    private int nwDstLength;

    @Pattern(regexp = IPv4.regex,
             message = "is an invalid IP format")
    private String nwSrcAddress;

    @Min(0)
    @Max(32)
    private int nwSrcLength;

    private boolean condInvert;
    private boolean matchForwardFlow;
    private boolean matchReturnFlow;
    private UUID[] inPorts;
    private boolean invInPorts;
    private UUID[] outPorts;
    private boolean invOutPorts;
    private UUID portGroup;
    private boolean invPortGroup;
    private UUID ipAddrGroupSrc;
    private boolean invIpAddrGroupSrc;
    private UUID ipAddrGroupDst;
    private boolean invIpAddrGroupDst;

    @Min(0x0600)
    @Max(0xFFFF)
    private Integer dlType;
    private boolean invDlType;
    private String dlSrc;
    @Since("2")
    private String dlSrcMask;
    private boolean invDlSrc;
    private String dlDst;
    @Since("2")
    private String dlDstMask;
    private boolean invDlDst;
    private int nwTos;
    private boolean invNwTos;
    private int nwProto;
    private boolean invNwProto;
    private boolean invNwSrc;
    private boolean invNwDst;

    @Since("2")
    @Pattern(regexp = FragmentPolicy.pattern,
             message = MessageProperty.FRAG_POLICY_UNDEFINED)
    private String fragmentPolicy;

    private Range<Integer> tpSrc;
    private Range<Integer> tpDst;

    private boolean invTpSrc;
    private boolean invTpDst;

    public boolean isCondInvert() {
        return condInvert;
    }

    public void setCondInvert(boolean condInvert) {
        this.condInvert = condInvert;
    }

    public boolean isMatchForwardFlow() {
        return matchForwardFlow;
    }

    public void setMatchForwardFlow(boolean matchForwardFlow) {
        this.matchForwardFlow = matchForwardFlow;
    }

    public boolean isMatchReturnFlow() {
        return matchReturnFlow;
    }

    public void setMatchReturnFlow(boolean matchReturnFlow) {
        this.matchReturnFlow = matchReturnFlow;
    }

    public UUID[] getInPorts() {
        return inPorts;
    }

    public void setInPorts(UUID[] inPorts) {
        this.inPorts = inPorts;
    }

    public boolean isInvInPorts() {
        return invInPorts;
    }

    public void setInvInPorts(boolean invInPorts) {
        this.invInPorts = invInPorts;
    }

    public UUID[] getOutPorts() {
        return outPorts;
    }

    public void setOutPorts(UUID[] outPorts) {
        this.outPorts = outPorts;
    }

    public boolean isInvOutPorts() {
        return invOutPorts;
    }

    public void setInvOutPorts(boolean invOutPorts) {
        this.invOutPorts = invOutPorts;
    }

    public boolean isInvPortGroup() {
        return invPortGroup;
    }

    public void setInvPortGroup(boolean invPortGroup) {
        this.invPortGroup = invPortGroup;
    }

    public UUID getPortGroup() {
        return portGroup;
    }

    public void setPortGroup(UUID portGroup) {
        this.portGroup = portGroup;
    }

    public UUID getIpAddrGroupSrc() {
        return ipAddrGroupSrc;
    }

    public void setIpAddrGroupSrc(UUID ipAddrGroupSrc) {
        this.ipAddrGroupSrc = ipAddrGroupSrc;
    }

    public boolean isInvIpAddrGroupSrc() {
        return invIpAddrGroupSrc;
    }

    public void setInvIpAddrGroupSrc(boolean invIpAddrGroupSrc) {
        this.invIpAddrGroupSrc = invIpAddrGroupSrc;
    }

    public UUID getIpAddrGroupDst() {
        return ipAddrGroupDst;
    }

    public void setIpAddrGroupDst(UUID ipAddrGroupDst) {
        this.ipAddrGroupDst = ipAddrGroupDst;
    }

    public boolean isInvIpAddrGroupDst() {
        return invIpAddrGroupDst;
    }

    public void setInvIpAddrGroupDst(boolean invIpAddrGroupDst) {
        this.invIpAddrGroupDst = invIpAddrGroupDst;
    }

    @Since("2")
    public String getFragmentPolicy() {
        return fragmentPolicy;
    }

    @Since("2")
    public void setFragmentPolicy(String fragmentPolicy) {
        this.fragmentPolicy = fragmentPolicy;
    }

    /**
     * Get the Data Layer Destination that this condition matches on
     *
     * @return A MAC address specified as "aa:bb:cc:dd:ee:ff"
     */
    public String getDlDst() {
        return dlDst;
    }

    /**
     * Gets the Data Layer Destination mask.
     *
     * @return
     *     Data layer destination mask in the form "ffff.0000.0000"
     */
    @Since("2")
    public String getDlDstMask() {
        return dlDstMask;
    }

    /**
     * Set the Data Layer Destination that this condition matches on
     *
     * @param dlDst
     *            A MAC address specified as "aa:bb:cc:dd:ee:ff"
     */
    public void setDlDst(String dlDst) {
        this.dlDst = dlDst;
    }

    /**
     * Set the Data Layer Destination mask. Bits that are set to zero
     * in the mask will be ignored when comparing a packet's source
     * MAC address against the condition's data layer source address.
     *
     * @param dlDstMask
     *     Mask in the form "ffff.0000.0000" (ignores the lower 32 bits).
     */
    @Since("2")
    public void setDlDstMask(String dlDstMask) {
        this.dlDstMask = dlDstMask;
    }

    /**
     * Set whether the match on the data layer destination should be inverted.
     * This will be stored but ignored until the DlDst has been set.
     *
     * @param invDlDst
     *            True if the condition should match packets whose data layer
     *            destination is NOT equal to the MAC set by 'setDlDst'
     *            False if the condition should match packets whose DlDst IS
     *            equal to that MAC
     */
    public void setInvDlDst(boolean invDlDst) {
        this.invDlDst = invDlDst;
    }

    /**
     * Find out whether this condition's match on the Data Layer Destination is
     * inverted.
     *
     * @return True if the condition matches packets whose data layer
     *         destination is NOT equal to the MAC set by 'setDlDst'. False if
     *         the condition matches packets whose DlDst is equal to that MAC.
     */
    public boolean isInvDlDst() {
        return invDlDst;
    }

    /**
     * Get the Data Layer Source that this condition matches on.
     *
     * @return A MAC address specified as "aa:bb:cc:dd:ee:ff"
     */
    public String getDlSrc() {
        return dlSrc;
    }

    /**
     * Gets the Data Layer Source mask.
     *
     * @return
     *     Data layer source mask in the form "ffff.0000.0000"
     */
    public String getDlSrcMask() {
        return dlSrcMask;
    }

    /**
     * Set the Data Layer Source address that this condition matches on
     *
     * @param dlSrc
     *            A MAC address specified as "aa:bb:cc:dd:ee:ff"
     */
    public void setDlSrc(String dlSrc) {
        this.dlSrc = dlSrc;
    }

    /**
     * Set the Data Layer Source mask. Bits that are set to zero in
     * the mask will be ignored when comparing a packet's source MAC
     * address against the condition's data layer source address.
     *
     * @param dlSrcMask
     *     Mask in the form "ffff.0000.0000" (ignores the lower 32 bits).
     */
    public void setDlSrcMask(String dlSrcMask) {
        this.dlSrcMask = dlSrcMask;
    }

    /**
     * Set whether the match on the data layer source should be inverted. This
     * will be stored but ignored until the DlSrc has been set.
     *
     * @param invDlSrc
     *            True if the condition should match packets whose data layer
     *            source is NOT equal to the MAC set by 'setDlSrc'. False if the
     *            condition should match packets whose DlSrc IS equal to that
     *            MAC
     */
    public void setInvDlSrc(boolean invDlSrc) {
        this.invDlSrc = invDlSrc;
    }

    /**
     * Find out whether this condition's match on the Data Layer Source is
     * inverted.
     *
     * @return True if the condition matches packets whose data layer source is
     *         NOT equal to the MAC set by 'setDlSrc'. False if the condition
     *         matches packets whose DlSrc is equal to that MAC.
     */
    public boolean isInvDlSrc() {
        return invDlSrc;
    }

    /**
     * Set the Data Layer Type (Ethertype) of packets matched by this condition
     *
     * @param dlType
     *            Ethertype value. We do not check the validity of the value
     *            provided: i.e. whether it's in the correct range for
     *            Ethertypes.
     */
    public void setDlType(Integer dlType) {
        this.dlType = dlType;
    }

    /**
     * Get the Data Layer Type (Ethertype) of packets matched by this condition
     *
     * @return The value of the Ethertype as a Integer if the condition matches
     *         Ethertype, otherwise null.
     */
    public Integer getDlType() {
        return dlType;
    }

    /**
     * Set whether the match on the data layer type should be inverted. This
     * will be stored but ignored until the DlType has been set.
     *
     * @param invDlType
     *            True if the condition should match packets whose data layer
     *            type is NOT equal to the Ethertype set by 'setDlType'. False
     *            if the condition should match packets whose DlType IS equal
     *            to that Ethertype.
     */
    public void setInvDlType(boolean invDlType) {
        this.invDlType = invDlType;
    }

    /**
     * Find out whether this condition's match on the Data Layer Type is
     * inverted
     *
     * @return True if the condition matches packets whose data layer type is
     *         NOT equal to the Ethertype set by 'setDlType'. False if the
     *         condition matches packets whose DlSrc is equal to that Ethertype
     */
    public boolean isInvDlType() {
        return invDlType;
    }

    public int getNwTos() {
        return nwTos;
    }

    public void setNwTos(int nwTos) {
        this.nwTos = nwTos;
    }

    public boolean isInvNwTos() {
        return invNwTos;
    }

    /**
     * @param invNwTos
     *            the invNwTos to set
     */
    public void setInvNwTos(boolean invNwTos) {
        this.invNwTos = invNwTos;
    }

    public int getNwProto() {
        return nwProto;
    }

    /**
     * @param nwProto
     *            the nwProto to set
     */
    public void setNwProto(int nwProto) {
        this.nwProto = nwProto;
    }

    public boolean isInvNwProto() {
        return invNwProto;
    }

    /**
     * @param invNwProto
     *            the invNwProto to set
     */
    public void setInvNwProto(boolean invNwProto) {
        this.invNwProto = invNwProto;
    }

    public String getNwSrcAddress() {
        return nwSrcAddress;
    }

    /**
     * @param nwSrcAddress
     *            the nwSrcAddress to set
     */
    public void setNwSrcAddress(String nwSrcAddress) {
        this.nwSrcAddress = nwSrcAddress;
    }

    public int getNwSrcLength() {
        return nwSrcLength;
    }

    /**
     * @param nwSrcLength
     *            the nwSrcLength to set
     */
    public void setNwSrcLength(int nwSrcLength) {
        this.nwSrcLength = nwSrcLength;
    }

    public boolean isInvNwSrc() {
        return invNwSrc;
    }

    /**
     * @param invNwSrc
     *            the invNwSrc to set
     */
    public void setInvNwSrc(boolean invNwSrc) {
        this.invNwSrc = invNwSrc;
    }

    public String getNwDstAddress() {
        return nwDstAddress;
    }

    /**
     * @param nwDstAddress the nwDstAddress to set
     */
    public void setNwDstAddress(String nwDstAddress) {
        this.nwDstAddress = nwDstAddress;
    }

    public int getNwDstLength() {
        return nwDstLength;
    }

    /**
     * @param nwDstLength the nwDstLength to set
     */
    public void setNwDstLength(int nwDstLength) {
        this.nwDstLength = nwDstLength;
    }

    public boolean isInvNwDst() {
        return invNwDst;
    }

    /**
     * @param invNwDst the invNwDst to set
     */
    public void setInvNwDst(boolean invNwDst) {
        this.invNwDst = invNwDst;
    }

    /**
     * @return the tpSrc
     */
    public Range<Integer> getTpSrc() {
        return tpSrc;
    }

    /**
     * @param tpSrc the tpSrc to set
     */
    public void setTpSrcStart(Range<Integer> tpSrc) {
        this.tpSrc = tpSrc;
    }

    /**
     * @return the invTpSrc
     */
    public boolean isInvTpSrc() {
        return invTpSrc;
    }

    /**
     * @param invTpSrc the invTpSrc to set
     */
    public void setInvTpSrc(boolean invTpSrc) {
        this.invTpSrc = invTpSrc;
    }

    /**
     * @return the tpDst
     */
    public Range<Integer> getTpDst() {
        return tpDst;
    }

    /**
     * @param tpDst the tpDst to set
     */
    public void setTpDst(Range<Integer> tpDst) {
        this.tpDst = tpDst;
    }

    /**
     * @param tpSrc the tpSrc to set
     */
    public void setTpSrc(Range<Integer> tpSrc) {
        this.tpSrc = tpSrc;
    }

    /**
     * @return the invTpDst
     */
    public boolean isInvTpDst() {
        return invTpDst;
    }

    /**
     * @param invTpDst
     *            the invTpDst to set
     */
    public void setInvTpDst(boolean invTpDst) {
        this.invTpDst = invTpDst;
    }

    protected org.midonet.midolman.rules.Condition makeCondition() {
        org.midonet.midolman.rules.Condition c =
            new org.midonet.midolman.rules.Condition();
        c.conjunctionInv = this.isCondInvert();
        c.matchForwardFlow = this.matchForwardFlow;
        c.matchReturnFlow = this.matchReturnFlow;
        if (this.getInPorts() != null) {
            c.inPortIds = new HashSet<UUID>(Arrays.asList(this.getInPorts()));
        } else {
            c.inPortIds = new HashSet<UUID>();
        }
        c.inPortInv = this.isInvInPorts();
        if (dlType != null)
            c.etherType = this.dlType;
        c.invDlType = this.isInvDlType();
        if (this.dlSrc != null)
            c.ethSrc = MAC.fromString(this.dlSrc);
        if (this.dlSrcMask != null)
            c.ethSrcMask = MAC.parseMask(this.dlSrcMask);
        c.invDlSrc = this.invDlSrc;
        if (this.dlDst != null)
            c.ethDst = MAC.fromString(this.dlDst);
        if (this.dlDstMask != null)
            c.dlDstMask = MAC.parseMask(this.dlDstMask);
        c.invDlDst = this.invDlDst;
        c.nwDstInv = this.isInvNwDst();
        if (this.getNwDstAddress() != null) {
            c.nwDstIp = new IPv4Subnet(
                IPv4Addr.fromString(this.getNwDstAddress()),
                this.getNwDstLength());
        }
        if (nwProto != 0)
            c.nwProto = (byte)nwProto;
        c.nwProtoInv = this.isInvNwProto();
        c.nwSrcInv = this.isInvNwSrc();
        if (this.getNwSrcAddress() != null) {
            c.nwSrcIp = new IPv4Subnet(
                IPv4Addr.fromString(this.getNwSrcAddress()),
                this.getNwSrcLength());
        }
        if (nwTos != 0)
            c.nwTos = (byte) nwTos;
        c.nwTosInv = this.isInvNwTos();
        c.fragmentPolicy = getAndValidateFragmentPolicy();
        if (this.getOutPorts() != null) {
            c.outPortIds = new HashSet<UUID>(Arrays.asList(this.getOutPorts()));
        } else {
            c.outPortIds = new HashSet<UUID>();
        }
        c.outPortInv = this.isInvOutPorts();
        if (tpDst != null) {
            c.tpDst = tpDst;
        }
        c.tpDstInv = this.isInvTpDst();
        if (tpSrc != null) {
            c.tpSrc = tpSrc;
        }
        c.tpSrcInv = this.isInvTpSrc();
        c.portGroup = this.portGroup;
        c.invPortGroup = this.invPortGroup;
        c.ipAddrGroupIdDst = this.ipAddrGroupDst;
        c.invIpAddrGroupIdDst = this.invIpAddrGroupDst;
        c.ipAddrGroupIdSrc = this.ipAddrGroupSrc;
        c.invIpAddrGroupIdSrc = this.invIpAddrGroupSrc;

        return c;
    }

    protected void setFromCondition(org.midonet.midolman.rules.Condition c) {
        this.setCondInvert(c.conjunctionInv);
        this.setInvInPorts(c.inPortInv);
        this.setInvOutPorts(c.outPortInv);
        this.setInvPortGroup(c.invPortGroup);
        this.setInvIpAddrGroupDst(c.invIpAddrGroupIdDst);
        this.setInvIpAddrGroupSrc(c.invIpAddrGroupIdSrc);
        this.setInvDlType(c.invDlType);
        this.setInvDlSrc(c.invDlSrc);
        this.setInvDlDst(c.invDlDst);
        this.setInvNwDst(c.nwDstInv);
        this.setInvNwProto(c.nwProtoInv);
        this.setInvNwSrc(c.nwSrcInv);
        this.setInvNwTos(c.nwTosInv);
        this.setInvTpDst(c.tpDstInv);
        this.setInvTpSrc(c.tpSrcInv);

        this.setMatchForwardFlow(c.matchForwardFlow);
        this.setMatchReturnFlow(c.matchReturnFlow);
        if (c.inPortIds != null) {
            this.setInPorts(c.inPortIds.toArray(new UUID[c.inPortIds.size()]));
        }
        if (c.outPortIds != null) {
            this.setOutPorts(c.outPortIds.toArray(new UUID[c.outPortIds.size()]));
        }
        this.setPortGroup(c.portGroup);
        this.setIpAddrGroupDst(c.ipAddrGroupIdDst);
        this.setIpAddrGroupSrc(c.ipAddrGroupIdSrc);
        this.setDlType(c.etherType);
        if (null != c.ethSrc)
            this.setDlSrc(c.ethSrc.toString());
        if (NO_MASK != c.ethSrcMask)
            this.setDlSrcMask(MAC.maskToString(c.ethSrcMask));
        if (null != c.ethDst)
            this.setDlDst(c.ethDst.toString());
        if (NO_MASK != c.dlDstMask)
            this.setDlDstMask(MAC.maskToString(c.dlDstMask));
        if (null != c.nwDstIp) {
            this.setNwDstAddress(c.nwDstIp.getAddress().toString());
            this.setNwDstLength(c.nwDstIp.getPrefixLen());
        }
        if (null != c.nwSrcIp) {
            this.setNwSrcAddress(c.nwSrcIp.getAddress().toString());
            this.setNwSrcLength(c.nwSrcIp.getPrefixLen());
        }
        if (null != c.nwProto)
            this.setNwProto(unsign(c.nwProto));
        if (null != c.nwTos)
            this.setNwTos(unsign(c.nwTos));
        this.setFragmentPolicy(c.fragmentPolicy.toString().toLowerCase());
        if (null != c.tpDst)
            this.setTpDst(c.tpDst);
        if (null != c.tpSrc)
            this.setTpSrc(c.tpSrc);
    }

    protected boolean hasL4Fields() {
        return tpDst != null || tpSrc != null;
    }

    /**
     * If fragmentPolicy is null, returns the appropriate default
     * fragment policy based on the condition's other properties. If
     * not, verifies that the value of fragmentPolicy is consistent
     * with the condition's other properties and then returns it.
     *
     * @throws BadRequestHttpException if fragmentPolicy is non-null
     * and not permitted due to the condition's other properties.
     */
    protected FragmentPolicy getAndValidateFragmentPolicy() {
        if (getFragmentPolicy() == null)
            return hasL4Fields() ? FragmentPolicy.HEADER : FragmentPolicy.ANY;

        FragmentPolicy fp = FragmentPolicy.valueOf(fragmentPolicy.toUpperCase());
        if (hasL4Fields() && fp.accepts(IPFragmentType.Later))
            throw new BadRequestHttpException(MessageProperty.getMessage(
                    MessageProperty.FRAG_POLICY_INVALID_FOR_L4_RULE));

        return fp;
    }
}
