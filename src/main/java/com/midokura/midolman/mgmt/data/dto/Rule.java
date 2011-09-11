/*
 * @(#)Route      1.6 11/09/10
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

/**
 * Class representing rule.
 * 
 * @version        1.6 11 Sept 2011
 * @author         Ryu Ishimoto
 */
@XmlRootElement
public class Rule {
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
    
    public static final String[] RuleTypes = {Accept, DNAT, Drop, 
        Jump, Reject, Return, RevDNAT, RevSNAT, SNAT};
    public static final String[] SimpleRuleTypes = {Accept, Drop,
        Reject, Return};
    public static final String[] NatRuleTypes = {DNAT, SNAT};
    public static final String[] RevNatRuleTypes = {RevDNAT, RevSNAT};
    public static final String[] RuleActions = {Accept, Continue, 
        Return};
    
    private UUID id = null;
    private UUID chainId = null;
    private boolean condInvert = false;
    private UUID[] inPorts = null;
    private boolean invInPorts = false;
    private UUID[] outPorts = null;
    private boolean invOutPorts = false;
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
    private UUID jumpChainId = null;
    private String jumpChainName = null;
    private String flowAction = null;
    private String[] natTargets = null;
    private int position;
    /**
     * @return the id
     */
    public UUID getId() {
        return id;
    }
    /**
     * @param id the id to set
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
     * @param chainId the chainId to set
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
     * @param condInvert the condInvert to set
     */
    public void setCondInvert(boolean condInvert) {
        this.condInvert = condInvert;
    }
    /**
     * @return the inPorts
     */
    public UUID[] getInPorts() {
        return inPorts;
    }
    /**
     * @param inPorts the inPorts to set
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
     * @param invInPorts the invInPorts to set
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
     * @param outPorts the outPorts to set
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
     * @param invOutPorts the invOutPorts to set
     */
    public void setInvOutPorts(boolean invOutPorts) {
        this.invOutPorts = invOutPorts;
    }
    /**
     * @return the nwTos
     */
    public int getNwTos() {
        return nwTos;
    }
    /**
     * @param nwTos the nwTos to set
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
     * @param invNwTos the invNwTos to set
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
     * @param nwProto the nwProto to set
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
     * @param invNwProto the invNwProto to set
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
     * @param nwSrcAddress the nwSrcAddress to set
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
     * @param nwSrcLength the nwSrcLength to set
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
     * @param invNwSrc the invNwSrc to set
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
     * @param nwDstAddress the nwDstAddress to set
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
     * @param nwDstLength the nwDstLength to set
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
     * @param invNwDst the invNwDst to set
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
     * @param tpSrcStart the tpSrcStart to set
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
     * @param tpSrcEnd the tpSrcEnd to set
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
     * @param invTpSrc the invTpSrc to set
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
     * @param tpDstStart the tpDstStart to set
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
     * @param tpDstEnd the tpDstEnd to set
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
     * @param invTpDst the invTpDst to set
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
     * @param type the type to set
     */
    public void setType(String type) {
        this.type = type;
    }
    /**
     * @return the jumpChainId
     */
    public UUID getJumpChainId() {
        return jumpChainId;
    }
    /**
     * @param jumpChainId the jumpChainId to set
     */
    public void setJumpChainId(UUID jumpChainId) {
        this.jumpChainId = jumpChainId;
    }
    /**
     * @return the jumpChainName
     */
    public String getJumpChainName() {
        return jumpChainName;
    }
    /**
     * @param jumpChainName the jumpChainName to set
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
     * @param flowAction the flowAction to set
     */
    public void setFlowAction(String flowAction) {
        this.flowAction = flowAction;
    }
    /**
     * @return the natTargets
     */
    public String[] getNatTargets() {
        return natTargets;
    }
    /**
     * @param natTargets the natTargets to set
     */
    public void setNatTargets(String[] natTargets) {
        this.natTargets = natTargets;
    }
    /**
     * @return the position
     */
    public int getPosition() {
        return position;
    }
    /**
     * @param position the position to set
     */
    public void setPosition(int position) {
        this.position = position;
    }

}
