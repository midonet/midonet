/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.filter;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.*;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;
import org.midonet.midolman.rules.Condition;
import org.midonet.packets.IPAddr;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.MAC;
import org.midonet.util.StringUtil;

import org.codehaus.jackson.annotate.JsonSubTypes;
import org.codehaus.jackson.annotate.JsonTypeInfo;


import static org.midonet.packets.Unsigned.unsign;

/**
 * Class representing rule.
 */
@XmlRootElement
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
        property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = AcceptRule.class, name = RuleType.Accept),
        @JsonSubTypes.Type(value = DropRule.class, name = RuleType.Drop),
        @JsonSubTypes.Type(value = ForwardDnatRule.class, name = RuleType.DNAT),
        @JsonSubTypes.Type(value = ForwardSnatRule.class,
                name = RuleType.SNAT),
        @JsonSubTypes.Type(value = JumpRule.class, name = RuleType.Jump),
        @JsonSubTypes.Type(value = RejectRule.class, name = RuleType.Reject),
        @JsonSubTypes.Type(value = ReturnRule.class, name = RuleType.Return),
        @JsonSubTypes.Type(value = ReverseDnatRule.class,
                name = RuleType.RevDNAT),
        @JsonSubTypes.Type(value = ReverseSnatRule.class,
                name = RuleType.RevSNAT)})
public abstract class Rule extends UriResource {

    private UUID id;
    private UUID chainId;

    @Min(1)
    private int position = 1;

    @Pattern(regexp = StringUtil.IP_ADDRESS_REGEX_PATTERN,
            message = "is an invalid IP format")
    private String nwDstAddress = null;

    @Min(0)
    @Max(32)
    private int nwDstLength;

    @Pattern(regexp = StringUtil.IP_ADDRESS_REGEX_PATTERN,
            message = "is an invalid IP format")
    private String nwSrcAddress = null;

    @Min(0)
    @Max(32)
    private int nwSrcLength;

    private Map<String, String> properties = new HashMap<String, String>();

    private boolean condInvert = false;
    private boolean matchForwardFlow = false;
    private boolean matchReturnFlow = false;
    private UUID[] inPorts = null;
    private boolean invInPorts = false;
    private UUID[] outPorts = null;
    private boolean invOutPorts = false;
    private UUID portGroup;
    private boolean invPortGroup;
    @Min(0x0600)
    @Max(0xFFFF)
    private Integer dlType = null;
    private boolean invDlType = false;
    private String dlSrc = null;
    private boolean invDlSrc = false;
    private String dlDst = null;
    private boolean invDlDst = false;
    private int nwTos;
    private boolean invNwTos = false;
    private int nwProto;
    private boolean invNwProto = false;
    private boolean invNwSrc = false;
    private boolean invNwDst = false;

    @Min(0)
    @Max(65535)
    private int tpSrcStart;

    @Min(0)
    @Max(65535)
    private int tpSrcEnd;

    @Min(0)
    @Max(65535)
    private int tpDstStart;

    @Min(0)
    @Max(65535)
    private int tpDstEnd;

    private boolean invTpSrc = false;
    private boolean invTpDst = false;

    /**
     * Default constructor
     */
    public Rule() {
        super();
    }

    /**
     * Constructor
     *
     * @param data
     *            Rule data object
     */
    public Rule(org.midonet.cluster.data.Rule data) {
        this.id = UUID.fromString(data.getId().toString());
        this.chainId = data.getChainId();
        this.position = data.getPosition();
        this.properties = data.getProperties();
        setFromCondition(data.getCondition());
    }

    @NotNull
    public abstract String getType();

    public abstract org.midonet.cluster.data.Rule toData();

    protected void setData(org.midonet.cluster.data.Rule data) {
        data.setId(id);
        data.setChainId(chainId);
        data.setPosition(position);
        data.setProperties(properties);
        data.setCondition(makeCondition());
    }

    /**
     * @return the id
     */
    public UUID getId() {
        return id;
    }

    /**
     * @param id
     *            the id to set
     */
    public void setId(UUID id) {
        this.id = id;
    }

    /**
     * @return the chainId
     */
    public UUID getChainId() {
        return chainId;
    }

    /**
     * @param chainId
     *            the chainId to set
     */
    public void setChainId(UUID chainId) {
        this.chainId = chainId;
    }

    /**
     * @return the position
     */
    public int getPosition() {
        return position;
    }

    /**
     * @param position
     *            the position to set
     */
    public void setPosition(int position) {
        this.position = position;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }

    /**
     * @return the condInvert
     */
    public boolean isCondInvert() {
        return condInvert;
    }

    /**
     * @param condInvert
     *            the condInvert to set
     */
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

    /**
     * @return the inPorts
     */
    public UUID[] getInPorts() {
        return inPorts;
    }

    /**
     * @param inPorts
     *            the inPorts to set
     */
    public void setInPorts(UUID[] inPorts) {
        this.inPorts = inPorts;
    }

    /**
     * @return the invInPorts
     */
    public boolean isInvInPorts() {
        return invInPorts;
    }

    /**
     * @param invInPorts
     *            the invInPorts to set
     */
    public void setInvInPorts(boolean invInPorts) {
        this.invInPorts = invInPorts;
    }

    /**
     * @return the outPorts
     */
    public UUID[] getOutPorts() {
        return outPorts;
    }

    /**
     * @param outPorts
     *            the outPorts to set
     */
    public void setOutPorts(UUID[] outPorts) {
        this.outPorts = outPorts;
    }

    /**
     * @return the invOutPorts
     */
    public boolean isInvOutPorts() {
        return invOutPorts;
    }

    /**
     * @param invOutPorts
     *            the invOutPorts to set
     */
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

    /**
     * Get the Data Layer Destination that this rule matches on.
     *
     * @return A MAC address specified as "aa:bb:cc:dd:ee:ff"
     */
    public String getDlDst() {
        return dlDst;
    }

    /**
     * Set the Data Layer Destination that this rule matches on.
     *
     * @param dlDst
     *            A MAC address specified as "aa:bb:cc:dd:ee:ff"
     */
    public void setDlDst(String dlDst) {
        this.dlDst = dlDst;
    }

    /**
     * Set whether the match on the data layer destination should be inverted.
     * This will be stored but ignored until the DlDst has been set.
     *
     * @param invDlDst
     *            True if the rule should match packets whose data layer
     *            destination is NOT equal to the MAC set by 'setDlDst'. False
     *            if the rule should match packets whose DlDst IS equal to that
     *            MAC.
     */
    public void setInvDlDst(boolean invDlDst) {
        this.invDlDst = invDlDst;
    }

    /**
     * Find out whether this rule's match on the Data Layer Destination is
     * inverted.
     *
     * @return True if the rule matches packets whose data layer destination is
     *         NOT equal to the MAC set by 'setDlDst'. False if the rule matches
     *         packets whose DlDst is equal to that MAC.
     */
    public boolean isInvDlDst() {
        return invDlDst;
    }

    /**
     * Get the Data Layer Source that this rule matches on.
     *
     * @return A MAC address specified as "aa:bb:cc:dd:ee:ff"
     */
    public String getDlSrc() {
        return dlSrc;
    }

    /**
     * Set the Data Layer Source address that this rule matches on.
     *
     * @param dlSrc
     *            A MAC address specified as "aa:bb:cc:dd:ee:ff"
     */
    public void setDlSrc(String dlSrc) {
        this.dlSrc = dlSrc;
    }

    /**
     * Set whether the match on the data layer source should be inverted. This
     * will be stored but ignored until the DlSrc has been set.
     *
     * @param invDlSrc
     *            True if the rule should match packets whose data layer source
     *            is NOT equal to the MAC set by 'setDlSrc'. False if the rule
     *            should match packets whose DlSrc IS equal to that MAC.
     */
    public void setInvDlSrc(boolean invDlSrc) {
        this.invDlSrc = invDlSrc;
    }

    /**
     * Find out whether this rule's match on the Data Layer Source is inverted.
     *
     * @return True if the rule matches packets whose data layer source is NOT
     *         equal to the MAC set by 'setDlSrc'. False if the rule matches
     *         packets whose DlSrc is equal to that MAC.
     */
    public boolean isInvDlSrc() {
        return invDlSrc;
    }

    /**
     * Set the Data Layer Type (Ethertype) of packets matched by this rule.
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
     * Get the Data Layer Type (Ethertype) of packets matched by this rule.
     *
     * @return The value of the Ethertype as a Integer if the rule matches
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
     *            True if the rule should match packets whose data layer type is
     *            NOT equal to the Ethertype set by 'setDlType'. False if the
     *            rule should match packets whose DlType IS equal to that
     *            Ethertype.
     */
    public void setInvDlType(boolean invDlType) {
        this.invDlType = invDlType;
    }

    /**
     * Find out whether this rule's match on the Data Layer Type is inverted.
     *
     * @return True if the rule matches packets whose data layer type is NOT
     *         equal to the Ethertype set by 'setDlType'. False if the rule
     *         matches packets whose DlSrc is equal to that Ethertype.
     */
    public boolean isInvDlType() {
        return invDlType;
    }

    /**
     * @return the nwTos
     */
    public int getNwTos() {
        return nwTos;
    }

    /**
     * @param nwTos
     *            the nwTos to set
     */
    public void setNwTos(int nwTos) {
        this.nwTos = nwTos;
    }

    /**
     * @return the invNwTos
     */
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

    /**
     * @return the nwProto
     */
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

    /**
     * @return the invNwProto
     */
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

    /**
     * @return the nwSrcAddress
     */
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

    /**
     * @return the nwSrcLength
     */
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

    /**
     * @return the invNwSrc
     */
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

    /**
     * @return the nwDstAddress
     */
    public String getNwDstAddress() {
        return nwDstAddress;
    }

    /**
     * @param nwDstAddress
     *            the nwDstAddress to set
     */
    public void setNwDstAddress(String nwDstAddress) {
        this.nwDstAddress = nwDstAddress;
    }

    /**
     * @return the nwDstLength
     */
    public int getNwDstLength() {
        return nwDstLength;
    }

    /**
     * @param nwDstLength
     *            the nwDstLength to set
     */
    public void setNwDstLength(int nwDstLength) {
        this.nwDstLength = nwDstLength;
    }

    /**
     * @return the invNwDst
     */
    public boolean isInvNwDst() {
        return invNwDst;
    }

    /**
     * @param invNwDst
     *            the invNwDst to set
     */
    public void setInvNwDst(boolean invNwDst) {
        this.invNwDst = invNwDst;
    }

    /**
     * @return the tpSrcStart
     */
    public int getTpSrcStart() {
        return tpSrcStart;
    }

    /**
     * @param tpSrcStart
     *            the tpSrcStart to set
     */
    public void setTpSrcStart(int tpSrcStart) {
        this.tpSrcStart = tpSrcStart;
    }

    /**
     * @return the tpSrcEnd
     */
    public int getTpSrcEnd() {
        return tpSrcEnd;
    }

    /**
     * @param tpSrcEnd
     *            the tpSrcEnd to set
     */
    public void setTpSrcEnd(int tpSrcEnd) {
        this.tpSrcEnd = tpSrcEnd;
    }

    /**
     * @return the invTpSrc
     */
    public boolean isInvTpSrc() {
        return invTpSrc;
    }

    /**
     * @param invTpSrc
     *            the invTpSrc to set
     */
    public void setInvTpSrc(boolean invTpSrc) {
        this.invTpSrc = invTpSrc;
    }

    /**
     * @return the tpDstStart
     */
    public int getTpDstStart() {
        return tpDstStart;
    }

    /**
     * @param tpDstStart
     *            the tpDstStart to set
     */
    public void setTpDstStart(int tpDstStart) {
        this.tpDstStart = tpDstStart;
    }

    /**
     * @return the tpDstEnd
     */
    public int getTpDstEnd() {
        return tpDstEnd;
    }

    /**
     * @param tpDstEnd
     *            the tpDstEnd to set
     */
    public void setTpDstEnd(int tpDstEnd) {
        this.tpDstEnd = tpDstEnd;
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

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getRule(getBaseUri(), id);
        } else {
            return null;
        }
    }

    protected Condition makeCondition() {
        Condition c = new Condition();
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
            c.dlType = this.dlType;
        c.invDlType = this.isInvDlType();
        if (this.dlSrc != null)
            c.dlSrc = MAC.fromString(this.dlSrc);
        c.invDlSrc = this.invDlSrc;
        if (this.dlDst != null)
            c.dlDst = MAC.fromString(this.dlDst);
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
        if (this.getOutPorts() != null) {
            c.outPortIds = new HashSet<UUID>(Arrays.asList(this.getOutPorts()));
        } else {
            c.outPortIds = new HashSet<UUID>();
        }
        c.outPortInv = this.isInvOutPorts();
        if (tpDstEnd != 0) {
            c.tpDstEnd = tpDstEnd;
            c.tpDstStart = tpDstStart;
        }
        c.tpDstInv = this.isInvTpDst();
        if (tpSrcEnd != 0) {
            c.tpSrcEnd = tpSrcEnd;
            c.tpSrcStart = tpSrcStart;
        }
        c.tpSrcInv = this.isInvTpSrc();
        c.portGroup = this.portGroup;
        c.invPortGroup = this.invPortGroup;
        return c;
    }

    private void setFromCondition(Condition c) {
        this.setCondInvert(c.conjunctionInv);
        this.setInvInPorts(c.inPortInv);
        this.setInvOutPorts(c.outPortInv);
        this.setInvPortGroup(c.invPortGroup);
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
        this.setDlType(c.dlType);
        if (null != c.dlSrc)
            this.setDlSrc(c.dlSrc.toString());
        if (null != c.dlDst)
            this.setDlDst(c.dlDst.toString());
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
        if (0 != c.tpDstEnd)
            this.setTpDstEnd(c.tpDstEnd);
        if (0 != c.tpDstStart)
            this.setTpDstStart(c.tpDstStart);
        if (0 != c.tpSrcEnd)
            this.setTpSrcEnd(c.tpSrcEnd);
        if (0 != c.tpSrcStart)
            this.setTpSrcStart(c.tpSrcStart);
    }

}
