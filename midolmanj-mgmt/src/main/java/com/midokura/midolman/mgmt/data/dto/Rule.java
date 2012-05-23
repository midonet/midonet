/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.rules.Condition;
import com.midokura.midolman.rules.ForwardNatRule;
import com.midokura.midolman.rules.JumpRule;
import com.midokura.midolman.rules.LiteralRule;
import com.midokura.midolman.rules.NatRule;
import com.midokura.midolman.rules.NatTarget;
import com.midokura.midolman.rules.ReverseNatRule;
import com.midokura.midolman.rules.RuleResult.Action;
import com.midokura.midolman.util.Net;

/**
 * Class representing rule.
 */
@XmlRootElement
public class Rule extends UriResource {
    public static final String Accept = "accept";
    public static final String Continue = "continue";
    public static final String Drop = "drop";
    public static final String Jump = "jump";
    public static final String Reject = "reject";
    public static final String Return = "return";
    public static final String DNAT = "dnat";
    public static final String SNAT = "snat";
    public static final String RevDNAT = "rev_dnat";
    public static final String RevSNAT = "rev_snat";

    public static final String[] RuleTypes = { Accept, DNAT, Drop, Jump,
            Reject, Return, RevDNAT, RevSNAT, SNAT };
    public static final String[] SimpleRuleTypes = { Accept, Drop, Reject,
            Return };
    public static final String[] NatRuleTypes = { DNAT, SNAT };
    public static final String[] RevNatRuleTypes = { RevDNAT, RevSNAT };
    public static final String[] RuleActions = { Accept, Continue, Return };

    private UUID id = null;
    private UUID chainId = null;
    private boolean condInvert = false;
    private boolean matchForwardFlow = false;
    private boolean matchReturnFlow = false;
    private UUID[] inPorts = null;
    private boolean invInPorts = false;
    private UUID[] outPorts = null;
    private boolean invOutPorts = false;
    private UUID portGroup;
    private boolean invPortGroup;
    private Short dlType = null;
    private boolean invDlType = false;
    private String dlSrc = null;
    private boolean invDlSrc = false;
    private String dlDst = null;
    private boolean invDlDst = false;
    private int nwTos;
    private boolean invNwTos = false;
    private int nwProto;
    private boolean invNwProto = false;
    private String nwSrcAddress = null;
    private int nwSrcLength;
    private boolean invNwSrc = false;
    private String nwDstAddress = null;
    private int nwDstLength;
    private boolean invNwDst = false;
    private short tpSrcStart;
    private short tpSrcEnd;
    private boolean invTpSrc = false;
    private short tpDstStart;
    private short tpDstEnd;
    private boolean invTpDst = false;
    private String type = null;
    private String jumpChainName = null;
    private String flowAction = null;
    private String[][][] natTargets = new String[2][2][];
    private int position = 1;

    /**
     * Default construtor
     */
    public Rule() {
        super();
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the rule
     * @param zkRule
     *            com.midokura.midolman.rules.Rule object
     */
    public Rule(UUID id, com.midokura.midolman.rules.Rule zkRule) {
        this.chainId = zkRule.chainId;
        setFromCondition(zkRule.getCondition());
        if (zkRule instanceof LiteralRule) {
            this.type = Rule.getActionString(zkRule.action);
        } else if (zkRule instanceof ForwardNatRule) {
            String[][][] targets = Rule
                    .makeNatTargetStrings(((ForwardNatRule) zkRule)
                            .getNatTargets());
            this.natTargets = targets;
            this.flowAction = Rule.getActionString(zkRule.action);
            if (((NatRule) zkRule).dnat) {
                this.type = Rule.DNAT;
            } else {
                this.type = Rule.SNAT;
            }
        } else if (zkRule instanceof ReverseNatRule) {
            if (((NatRule) zkRule).dnat) {
                this.type = Rule.RevDNAT;
            } else {
                this.type = Rule.RevSNAT;
            }
            this.flowAction = Rule.getActionString(zkRule.action);
        } else {
            this.jumpChainName = ((JumpRule) zkRule).jumpToChainName;
        }
        this.id = id;
        this.position = zkRule.position;
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
    public void setDlType(Short dlType) {
        this.dlType = dlType;
    }

    /**
     * Get the Data Layer Type (Ethertype) of packets matched by this rule.
     *
     * @return The value of the Ethertype as a Short if the rule matches
     *         Ethertype, otherwise null.
     */
    public Short getDlType() {
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
    public short getTpSrcStart() {
        return tpSrcStart;
    }

    /**
     * @param tpSrcStart
     *            the tpSrcStart to set
     */
    public void setTpSrcStart(short tpSrcStart) {
        this.tpSrcStart = tpSrcStart;
    }

    /**
     * @return the tpSrcEnd
     */
    public short getTpSrcEnd() {
        return tpSrcEnd;
    }

    /**
     * @param tpSrcEnd
     *            the tpSrcEnd to set
     */
    public void setTpSrcEnd(short tpSrcEnd) {
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
    public short getTpDstStart() {
        return tpDstStart;
    }

    /**
     * @param tpDstStart
     *            the tpDstStart to set
     */
    public void setTpDstStart(short tpDstStart) {
        this.tpDstStart = tpDstStart;
    }

    /**
     * @return the tpDstEnd
     */
    public short getTpDstEnd() {
        return tpDstEnd;
    }

    /**
     * @param tpDstEnd
     *            the tpDstEnd to set
     */
    public void setTpDstEnd(short tpDstEnd) {
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
     * @return the type
     */
    public String getType() {
        return type;
    }

    /**
     * @param type
     *            the type to set
     */
    public void setType(String type) {
        this.type = type;
    }

    /**
     * @return the jumpChainName
     */
    public String getJumpChainName() {
        return jumpChainName;
    }

    /**
     * @param jumpChainName
     *            the jumpChainName to set
     */
    public void setJumpChainName(String jumpChainName) {
        this.jumpChainName = jumpChainName;
    }

    /**
     * @return the flowAction
     */
    public String getFlowAction() {
        return flowAction;
    }

    /**
     * @param flowAction
     *            the flowAction to set
     */
    public void setFlowAction(String flowAction) {
        this.flowAction = flowAction;
    }

    /**
     * @return the natTargets
     */
    public String[][][] getNatTargets() {
        return natTargets;
    }

    /**
     * @param natTargets
     *            the natTargets to set
     */
    public void setNatTargets(String[][][] natTargets) {
        this.natTargets = natTargets;
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

    public static String getActionString(Action a) {
        switch (a) {
        case ACCEPT:
            return Rule.Accept;
        case CONTINUE:
            return Rule.Continue;
        case DROP:
            return Rule.Drop;
        case JUMP:
            return Rule.Jump;
        case REJECT:
            return Rule.Reject;
        case RETURN:
            return Rule.Return;
        default:
            throw new IllegalArgumentException("Invalid action passed in.");
        }
    }

    private static Action getAction(String type) {
        // ACCEPT, CONTINUE, DROP, JUMP, REJECT, RETURN
        if (type.equals(Rule.Accept)) {
            return Action.ACCEPT;
        } else if (type.equals(Rule.Continue)) {
            return Action.CONTINUE;
        } else if (type.equals(Rule.Drop)) {
            return Action.DROP;
        } else if (type.equals(Rule.Jump)) {
            return Action.JUMP;
        } else if (type.equals(Rule.Reject)) {
            return Action.REJECT;
        } else if (type.equals(Rule.Return)) {
            return Action.RETURN;
        } else {
            return null;
        }
    }

    private Condition makeCondition() {
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
            c.nwDstIp = Net.convertStringAddressToInt(this.getNwDstAddress());
        }
        c.nwDstLength = (byte) this.getNwDstLength();
        c.nwProto = (byte) this.getNwProto();
        c.nwProtoInv = this.isInvNwProto();
        c.nwSrcInv = this.isInvNwSrc();
        if (this.getNwSrcAddress() != null) {
            c.nwSrcIp = Net.convertStringAddressToInt(this.getNwSrcAddress());
        }
        c.nwSrcLength = (byte) this.getNwSrcLength();
        c.nwTos = (byte) this.getNwTos();
        c.nwTosInv = this.isInvNwTos();
        if (this.getOutPorts() != null) {
            c.outPortIds = new HashSet<UUID>(Arrays.asList(this.getOutPorts()));
        } else {
            c.outPortIds = new HashSet<UUID>();
        }
        c.outPortInv = this.isInvOutPorts();
        c.tpDstEnd = this.getTpDstEnd();
        c.tpDstInv = this.isInvTpDst();
        c.tpDstStart = this.getTpDstStart();
        c.tpSrcEnd = this.getTpSrcEnd();
        c.tpSrcInv = this.isInvTpSrc();
        c.tpSrcStart = this.getTpSrcStart();
        c.portGroup = this.portGroup;
        c.invPortGroup = this.invPortGroup;
        return c;
    }

    private static Set<NatTarget> makeNatTargets(String[][][] natTargets) {
        Set<NatTarget> targets = new HashSet<NatTarget>(natTargets.length);
        for (String[][] natTarget : natTargets) {
            String[] addressRange = natTarget[0];
            String[] portRange = natTarget[1];
            NatTarget t = new NatTarget(
                    Net.convertStringAddressToInt(addressRange[0]),
                    Net.convertStringAddressToInt(addressRange[1]),
                    (short) Integer.parseInt(portRange[0]),
                    (short) Integer.parseInt(portRange[1]));
            targets.add(t);
        }
        return targets;
    }

    public static String[][][] makeNatTargetStrings(Set<NatTarget> natTargets) {
        List<String[][]> targets = new ArrayList<String[][]>(natTargets.size());
        for (NatTarget t : natTargets) {
            String[] addressRange = { Net.convertIntAddressToString(t.nwStart),
                    Net.convertIntAddressToString(t.nwEnd) };
            String[] portRange = { String.valueOf(t.tpStart),
                    String.valueOf(t.tpEnd) };
            String[][] target = { addressRange, portRange };
            targets.add(target);
        }
        return targets.toArray(new String[2][2][targets.size()]);
    }

    public com.midokura.midolman.rules.Rule toZkRule(UUID jumpChainID) {
        Condition cond = makeCondition();
        String type = this.getType();
        Action action = getAction(type);
        com.midokura.midolman.rules.Rule r = null;
        if (Arrays.asList(Rule.SimpleRuleTypes).contains(type)) {
            r = new LiteralRule(cond, action);
        } else if (Arrays.asList(Rule.NatRuleTypes).contains(type)) {
            Set<NatTarget> targets = makeNatTargets(this.getNatTargets());
            r = new ForwardNatRule(cond, getAction(this.getFlowAction()),
                    chainId, position, type.equals(Rule.DNAT), targets);
        } else if (Arrays.asList(Rule.RevNatRuleTypes).contains(type)) {
            r = new ReverseNatRule(cond, getAction(this.getFlowAction()),
                    type.equals(Rule.RevDNAT));
        } else {
            // Jump
            r = new JumpRule(cond, jumpChainID, getJumpChainName());
        }
        r.chainId = chainId;
        r.position = position;
        return r;
    }

    public void setFromCondition(Condition c) {
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
        if (c.nwDstIp != 0)
            this.setNwDstAddress(Net.convertIntAddressToString(c.nwDstIp));
        if (c.nwSrcIp != 0)
            this.setNwSrcAddress(Net.convertIntAddressToString(c.nwSrcIp));
        this.setNwDstLength(c.nwDstLength);
        this.setNwSrcLength(c.nwSrcLength);
        this.setNwProto(c.nwProto);
        this.setNwTos(c.nwTos);
        this.setTpDstEnd(c.tpDstEnd);
        this.setTpDstStart(c.tpDstStart);
        this.setTpSrcEnd(c.tpSrcEnd);
        this.setTpSrcStart(c.tpSrcStart);
    }

    @Override
    public String toString() {
        return "dto.Rule: " + toZkRule(null).toString();
    }
}
