package org.midonet.client.resource;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import org.midonet.client.VendorMediaType;
import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoRule;

public class Rule extends ResourceBase<Rule, DtoRule> {


    public Rule(WebResource resource, URI uriForCreation, DtoRule r) {
        super(resource, uriForCreation, r,
                VendorMediaType.APPLICATION_RULE_JSON);
    }

    /**
     * Gets URI for this rule.
     *
     * @return URI for this rule
     */
    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    /**
     * Returns boolean for CondInvert.
     *
     * @return
     */
    public boolean isCondInvert() {
        return principalDto.isCondInvert();
    }

    /**
     * Returns boolean for invDlDst.
     *
     * @return
     */
    public boolean isInvDlDst() {
        return principalDto.isInvDlDst();
    }

    /**
     * Returns boolean fo invDlSrc.
     *
     * @return
     */
    public boolean isInvDlSrc() {
        return principalDto.isInvDlSrc();
    }

    /**
     * Returns boolean for invDlType.
     *
     * @return
     */
    public boolean isInvDlType() {
        return principalDto.isInvDlType();
    }

    /**
     * Returns boolean for invInPorts.
     *
     * @return
     */
    public boolean isInvInPorts() {
        return principalDto.isInvInPorts();
    }

    /**
     * Returns boolean for invNwDst.
     *
     * @return
     */
    public boolean isInvNwDst() {
        return principalDto.isInvNwDst();
    }

    /**
     * Returns boolean for invNwProto.
     *
     * @return
     */
    public boolean isInvNwProto() {
        return principalDto.isInvNwProto();
    }

    /**
     * Returns boolean for invNwSrc.
     *
     * @return
     */
    public boolean isInvNwSrc() {
        return principalDto.isInvNwSrc();
    }

    /**
     * Returns boolean for invNwTos.
     *
     * @return
     */
    public boolean isInvNwTos() {
        return principalDto.isInvNwTos();
    }

    /**
     * Returns the rule's packet fragment policy.
     *
     * @return "any", "header", "nonheader", or "unfragmented".
     */
    public String getFragmentPolicy() {
        return principalDto.getFragmentPolicy();
    }

    /**
     * Returns boolean for invOutPorts.
     *
     * @return
     */
    public boolean isInvOutPorts() {
        return principalDto.isInvOutPorts();
    }

    /**
     * Returns boolean for invPortGroup.
     *
     * @return
     */
    public boolean isInvPortGroup() {
        return principalDto.isInvPortGroup();
    }

    /**
     * Returns boolean for invTpDst.
     *
     * @return
     */
    public boolean isInvTpDst() {
        return principalDto.isInvTpDst();
    }

    /**
     * Returns boolean for invTpSrc.
     *
     * @return
     */
    public boolean isInvTpSrc() {
        return principalDto.isInvTpSrc();
    }

    /**
     * Returns boolean for matchForwardFlow.
     *
     * @return
     */
    public boolean isMatchForwardFlow() {
        return principalDto.isMatchForwardFlow();
    }

    /**
     * Returns bolean for matchReturnFlow.
     *
     * @return
     */
    public boolean isMatchReturnFlow() {
        return principalDto.isMatchReturnFlow();
    }

    /**
     * Gets ID for chain that this rule belongs to.
     *
     * @return ID of the chain.
     */
    public UUID getChainId() {
        return principalDto.getChainId();
    }

    /**
     * Gets destination address in data link..
     *
     * @return
     */
    public String getDlDst() {
        return principalDto.getDlDst();
    }

    /**
     * Gets source address in data link.
     *
     * @return
     */
    public String getDlSrc() {
        return principalDto.getDlSrc();
    }

    /**
     * Gets type of data link.
     *
     * @return
     */
    public Integer getDlType() {
        return principalDto.getDlType();
    }

    /**
     * Gets flow action.
     *
     * @return
     */
    public String getFlowAction() {
        return principalDto.getFlowAction();
    }

    /**
     * Gets ID of this rule.
     *
     * @return UUID of this rule.
     */
    public UUID getId() {
        return principalDto.getId();
    }

    /**
     * Gets ingress port IDs for this rule.
     *
     * @return
     */
    public UUID[] getInPorts() {
        return principalDto.getInPorts();
    }

    /**
     * Gets name of the chain to jump for this rule.
     *
     * @return
     */
    public String getJumpChainName() {
        return principalDto.getJumpChainName();
    }

    /**
     * Gets id of the chain to jump for this rule.
     *
     * @return
     */
    public UUID getJumpChainId() {
        return principalDto.getJumpChainId();
    }

    /**
     * Gets nat target for this rule.
     *
     * @return
     */
    public DtoRule.DtoNatTarget[] getNatTargets() {
        return principalDto.getNatTargets();
    }

    /**
     * Gets destination network address for this rule.
     *
     * @return
     */
    public String getNwDstAddress() {
        return principalDto.getNwDstAddress();
    }

    /**
     * Gets destination network length.
     *
     * @return
     */
    public int getNwDstLength() {
        return principalDto.getNwDstLength();
    }

    /**
     * Gets network protocol number for this rule.
     *
     * @return
     */
    public int getNwProto() {
        return principalDto.getNwProto();
    }

    /**
     * Gets network source address for this rule.
     *
     * @return
     */
    public String getNwSrcAddress() {
        return principalDto.getNwSrcAddress();
    }

    /**
     * Gets network source length for this rule.
     *
     * @return
     */
    public int getNwSrcLength() {
        return principalDto.getNwSrcLength();
    }

    /**
     * Gets network TOS for this rule.
     *
     * @return
     */
    public int getNwTos() {
        return principalDto.getNwTos();
    }

    /**
     * Gets out port IDs for this rule.
     *
     * @return
     */
    public UUID[] getOutPorts() {
        return principalDto.getOutPorts();
    }

    /**
     * Gets port group ID for this rule.
     *
     * @return
     */
    public UUID getPortGroup() {
        return principalDto.getPortGroup();
    }

    /**
     * Gets position of this rule.
     *
     * @return
     */
    public int getPosition() {
        return principalDto.getPosition();
    }

    /**
     * Gets properties for this rule.
     *
     * @return
     */
    public Map<String, String> getProperties() {
        return principalDto.getProperties();
    }

    /**
     * Gets destination port range for this rule.
     *
     * @return
     */
    public DtoRule.DtoRange<Integer> getTpDst() {
        return principalDto.getTpDst();
    }

    /**
     * Gets source port range for this rule.
     *
     * @return
     */
    public DtoRule.DtoRange<Integer> getTpSrc() {
        return principalDto.getTpSrc();
    }

    /**
     * Gets type for this rule.
     *
     * @return
     */
    public String getType() {
        return principalDto.getType();
    }

    /**
     * Sets invNwDst.
     *
     * @param invNwDst
     * @return this
     */
    public Rule invNwDst(boolean invNwDst) {
        principalDto.setInvNwDst(invNwDst);
        return this;
    }

    /**
     * Sets invNwTos.
     *
     * @param invNwTos
     * @return this
     */
    public Rule invNwTos(boolean invNwTos) {
        principalDto.setInvNwTos(invNwTos);
        return this;
    }

    /**
     * Sets the packet fragmentation statuses the rule matches.
     *
     * @param fragmentPolicy
     *      Can be "any", "header", "nonheader", or "unfragmented".
     */
    public Rule setFragmentPolicy(String fragmentPolicy) {
        principalDto.setFragmentPolicy(fragmentPolicy);
        return this;
    }

    /**
     * Sets chainId.
     *
     * @param chainId
     * @return this
     */
    public Rule chainId(UUID chainId) {
        principalDto.setChainId(chainId);
        return this;
    }

    /**
     * Sets position.
     *
     * @param position
     * @return this
     */
    public Rule position(int position) {
        principalDto.setPosition(position);
        return this;
    }

    /**
     * sets invTpDest
     *
     * @param invTpDst
     * @return this
     */
    public Rule invTpDst(boolean invTpDst) {
        principalDto.setInvTpDst(invTpDst);
        return this;
    }

    /**
     * Sets invNwSrc.
     *
     * @param invNwSrc
     * @return this
     */
    public Rule invNwSrc(boolean invNwSrc) {
        principalDto.setInvNwSrc(invNwSrc);
        return this;
    }

    /**
     * Sets invOutPorts
     *
     * @param invOutPorts
     * @return this
     */
    public Rule invOutPorts(boolean invOutPorts) {
        principalDto.setInvOutPorts(invOutPorts);
        return this;
    }

    /**
     * Sets outPorts.
     *
     * @param outPorts
     * @return this
     */
    public Rule outPorts(UUID[] outPorts) {
        principalDto.setOutPorts(outPorts);
        return this;
    }

    /**
     * Sets nwProto.
     *
     * @param nwProto
     * @return
     */
    public Rule nwProto(int nwProto) {
        principalDto.setNwProto(nwProto);
        return this;
    }

    /**
     * Sets invDlType.
     *
     * @param invDlType
     * @return this
     */
    public Rule invDlType(boolean invDlType) {
        principalDto.setInvDlType(invDlType);
        return this;
    }

    /**
     * Sets tpDst.
     *
     * @param tpDst
     * @return this
     */
    public Rule tpDst(DtoRule.DtoRange<Integer> tpDst) {
        principalDto.setTpDst(tpDst);
        return this;
    }

    /**
     * Sets dlDst.
     *
     * @param dlDst
     * @return this
     */
    public Rule dlDst(String dlDst) {
        principalDto.setDlDst(dlDst);
        return this;
    }

    /**
     * Sets portGroups
     *
     * @param portGroup
     * @return this
     */
    public Rule portGroup(UUID portGroup) {
        principalDto.setPortGroup(portGroup);
        return this;
    }

    /**
     * Sets matchReturnFlow.
     *
     * @param matchReturnFlow
     * @return this
     */
    public Rule matchReturnFlow(boolean matchReturnFlow) {
        principalDto.setMatchReturnFlow(matchReturnFlow);
        return this;
    }

    /**
     * Sets nwDstAddress.
     *
     * @param nwDstAddress
     * @return this
     */
    public Rule nwDstAddress(String nwDstAddress) {
        principalDto.setNwDstAddress(nwDstAddress);
        return this;
    }

    /**
     * Sets invDlDst.
     *
     * @param invDlDst
     * @return this
     */
    public Rule invDlDst(boolean invDlDst) {
        principalDto.setInvDlDst(invDlDst);
        return this;
    }

    /**
     * Sets invInPorts.
     *
     * @param invInPorts
     * @return this
     */
    public Rule invInPorts(boolean invInPorts) {
        principalDto.setInvInPorts(invInPorts);
        return this;
    }

    /**
     * Sets dlType.
     *
     * @param dlType
     * @return this
     */
    public Rule dlType(Integer dlType) {
        principalDto.setDlType(dlType);
        return this;
    }

    /**
     * Sets natTargets.
     *
     * @param natTargets
     * @return this
     */
    public Rule natTargets(DtoRule.DtoNatTarget[] natTargets) {
        principalDto.setNatTargets(natTargets);
        return this;
    }

    /**
     * Sets invTpSrc.
     *
     * @param invTpSrc
     * @return this
     */
    public Rule invTpSrc(boolean invTpSrc) {
        principalDto.setInvTpSrc(invTpSrc);
        return this;
    }

    /**
     * Sets properties.
     *
     * @param properties
     * @return this
     */
    public Rule properties(Map<String, String> properties) {
        principalDto.setProperties(properties);
        return this;
    }

    /**
     * Sets jumpChainName.
     *
     * @param jumpChainName
     * @return this
     */
    public Rule jumpChainName(String jumpChainName) {
        principalDto.setJumpChainName(jumpChainName);
        return this;
    }

    /**
     * Sets jumpChainName.
     *
     * @param jumpChainId
     * @return this
     */
    public Rule jumpChainId(UUID jumpChainId) {
        principalDto.setJumpChainId(jumpChainId);
        return this;
    }

    /**
     * Sets matchForwardFlow
     *
     * @param matchForwardFlow
     * @return this
     */
    public Rule matchForwardFlow(boolean matchForwardFlow) {
        principalDto.setMatchForwardFlow(matchForwardFlow);
        return this;
    }

    /**
     * Sets nwSrcLength.
     *
     * @param nwSrcLength
     * @return this
     */
    public Rule nwSrcLength(int nwSrcLength) {
        principalDto.setNwSrcLength(nwSrcLength);
        return this;
    }

    /**
     * Sets flowAction.
     *
     * @param flowAction
     * @return this
     */
    public Rule flowAction(String flowAction) {
        principalDto.setFlowAction(flowAction);
        return this;
    }

    /**
     * Sets condInvert.
     *
     * @param condInvert
     * @return this
     */
    public Rule condInvert(boolean condInvert) {
        principalDto.setCondInvert(condInvert);
        return this;
    }

    /**
     * Sets dlSrc.
     *
     * @param dlSrc
     * @return this
     */
    public Rule dlSrc(String dlSrc) {
        principalDto.setDlSrc(dlSrc);
        return this;
    }

    /**
     * Sets invDlSrc.
     *
     * @param invDlSrc
     * @return this
     */
    public Rule invDlSrc(boolean invDlSrc) {
        principalDto.setInvDlSrc(invDlSrc);
        return this;
    }

    /**
     * Sets nwDstLength.
     *
     * @param nwDstLength
     * @return this
     */
    public Rule nwDstLength(int nwDstLength) {
        principalDto.setNwDstLength(nwDstLength);
        return this;
    }

    /**
     * Sets invPortGroup.
     *
     * @param invPortGroup
     * @return this
     */
    public Rule invPortGroup(boolean invPortGroup) {
        principalDto.setInvPortGroup(invPortGroup);
        return this;
    }

    /**
     * Sets nwTos.
     *
     * @param nwTos
     * @return this
     */
    public Rule nwTos(int nwTos) {
        principalDto.setNwTos(nwTos);
        return this;
    }

    /**
     * Sets nwSrcAddress.
     *
     * @param nwSrcAddress
     * @return this
     */
    public Rule nwSrcAddress(String nwSrcAddress) {
        principalDto.setNwSrcAddress(nwSrcAddress);
        return this;
    }

    /**
     * Sets inports.
     *
     * @param inPorts
     * @return this
     */
    public Rule inPorts(UUID[] inPorts) {
        principalDto.setInPorts(inPorts);
        return this;
    }

    /**
     * Sets tpDst.
     *
     * @param tpSrc
     * @return this
     */
    public Rule tpSrc(DtoRule.DtoRange<Integer> tpSrc) {
        principalDto.setTpSrc(tpSrc);
        return this;
    }

    /**
     * Sets invNwProto.
     *
     * @param invNwProto
     * @return this
     */
    public Rule invNwProto(boolean invNwProto) {
        principalDto.setInvNwProto(invNwProto);
        return this;
    }

    /**
     * Sets type.
     *
     * @param type
     * @return this
     */
    public Rule type(String type) {
        principalDto.setType(type);
        return this;
    }
}
