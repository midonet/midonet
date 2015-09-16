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

package org.midonet.client.resource;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoRule;
import org.midonet.cluster.services.rest_api.MidonetMediaTypes;

public class Rule extends ResourceBase<Rule, DtoRule> {


    public Rule(WebResource resource, URI uriForCreation, DtoRule r) {
        super(resource, uriForCreation, r,
                MidonetMediaTypes.APPLICATION_RULE_JSON_V2());
    }

    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    public boolean isCondInvert() {
        return principalDto.isCondInvert();
    }

    public boolean isInvDlDst() {
        return principalDto.isInvDlDst();
    }

    public boolean isInvDlSrc() {
        return principalDto.isInvDlSrc();
    }

    public boolean isInvDlType() {
        return principalDto.isInvDlType();
    }

    public boolean isInvInPorts() {
        return principalDto.isInvInPorts();
    }

    public boolean isInvNwDst() {
        return principalDto.isInvNwDst();
    }

    public boolean isInvNwProto() {
        return principalDto.isInvNwProto();
    }

    public boolean isInvNwSrc() {
        return principalDto.isInvNwSrc();
    }

    public boolean isInvNwTos() {
        return principalDto.isInvNwTos();
    }

    public String getFragmentPolicy() {
        return principalDto.getFragmentPolicy();
    }

    public boolean isInvOutPorts() {
        return principalDto.isInvOutPorts();
    }

    public boolean isInvPortGroup() {
        return principalDto.isInvPortGroup();
    }

    public boolean isInvTpDst() {
        return principalDto.isInvTpDst();
    }

    public boolean isInvTpSrc() {
        return principalDto.isInvTpSrc();
    }

    public boolean isMatchForwardFlow() {
        return principalDto.isMatchForwardFlow();
    }

    public boolean isMatchReturnFlow() {
        return principalDto.isMatchReturnFlow();
    }

    public UUID getChainId() {
        return principalDto.getChainId();
    }

    public String getDlDst() {
        return principalDto.getDlDst();
    }

    public String getDlSrc() {
        return principalDto.getDlSrc();
    }

    public Integer getDlType() {
        return principalDto.getDlType();
    }

    public String getFlowAction() {
        return principalDto.getFlowAction();
    }

    public UUID getId() {
        return principalDto.getId();
    }

    public UUID[] getInPorts() {
        return principalDto.getInPorts();
    }

    public String getJumpChainName() {
        return principalDto.getJumpChainName();
    }

    public UUID getJumpChainId() {
        return principalDto.getJumpChainId();
    }

    public DtoRule.DtoNatTarget[] getNatTargets() {
        return principalDto.getNatTargets();
    }

    public String getNwDstAddress() {
        return principalDto.getNwDstAddress();
    }

    public int getNwDstLength() {
        return principalDto.getNwDstLength();
    }

    public int getNwProto() {
        return principalDto.getNwProto();
    }

    public String getNwSrcAddress() {
        return principalDto.getNwSrcAddress();
    }

    public int getNwSrcLength() {
        return principalDto.getNwSrcLength();
    }

    public int getNwTos() {
        return principalDto.getNwTos();
    }

    public UUID[] getOutPorts() {
        return principalDto.getOutPorts();
    }

    public UUID getPortGroup() {
        return principalDto.getPortGroup();
    }

    public int getPosition() {
        return principalDto.getPosition();
    }

    public String getMeterName() {
        return principalDto.getMeterName();
    }

    public Map<String, String> getProperties() {
        return principalDto.getProperties();
    }

    public DtoRule.DtoRange<Integer> getTpDst() {
        return principalDto.getTpDst();
    }

    public DtoRule.DtoRange<Integer> getTpSrc() {
        return principalDto.getTpSrc();
    }

    public String getType() {
        return principalDto.getType();
    }

    public Rule invNwDst(boolean invNwDst) {
        principalDto.setInvNwDst(invNwDst);
        return this;
    }

    public Rule invNwTos(boolean invNwTos) {
        principalDto.setInvNwTos(invNwTos);
        return this;
    }

    public Rule setFragmentPolicy(String fragmentPolicy) {
        principalDto.setFragmentPolicy(fragmentPolicy);
        return this;
    }

    public Rule chainId(UUID chainId) {
        principalDto.setChainId(chainId);
        return this;
    }

    public Rule position(int position) {
        principalDto.setPosition(position);
        return this;
    }

    public Rule meterName(String meterName) {
        principalDto.setMeterName(meterName);
        return this;
    }

    public Rule invTpDst(boolean invTpDst) {
        principalDto.setInvTpDst(invTpDst);
        return this;
    }

    public Rule invNwSrc(boolean invNwSrc) {
        principalDto.setInvNwSrc(invNwSrc);
        return this;
    }

    public Rule invOutPorts(boolean invOutPorts) {
        principalDto.setInvOutPorts(invOutPorts);
        return this;
    }

    public Rule outPorts(UUID[] outPorts) {
        principalDto.setOutPorts(outPorts);
        return this;
    }

    public Rule nwProto(int nwProto) {
        principalDto.setNwProto(nwProto);
        return this;
    }

    public Rule invDlType(boolean invDlType) {
        principalDto.setInvDlType(invDlType);
        return this;
    }

    public Rule tpDst(DtoRule.DtoRange<Integer> tpDst) {
        principalDto.setTpDst(tpDst);
        return this;
    }

    public Rule dlDst(String dlDst) {
        principalDto.setDlDst(dlDst);
        return this;
    }

    public Rule portGroup(UUID portGroup) {
        principalDto.setPortGroup(portGroup);
        return this;
    }

    public Rule matchReturnFlow(boolean matchReturnFlow) {
        principalDto.setMatchReturnFlow(matchReturnFlow);
        return this;
    }

    public Rule nwDstAddress(String nwDstAddress) {
        principalDto.setNwDstAddress(nwDstAddress);
        return this;
    }

    public Rule invDlDst(boolean invDlDst) {
        principalDto.setInvDlDst(invDlDst);
        return this;
    }

    public Rule invInPorts(boolean invInPorts) {
        principalDto.setInvInPorts(invInPorts);
        return this;
    }

    public Rule dlType(Integer dlType) {
        principalDto.setDlType(dlType);
        return this;
    }

    public Rule natTargets(DtoRule.DtoNatTarget[] natTargets) {
        principalDto.setNatTargets(natTargets);
        return this;
    }

    public Rule invTpSrc(boolean invTpSrc) {
        principalDto.setInvTpSrc(invTpSrc);
        return this;
    }

    public Rule properties(Map<String, String> properties) {
        principalDto.setProperties(properties);
        return this;
    }

    public Rule jumpChainName(String jumpChainName) {
        principalDto.setJumpChainName(jumpChainName);
        return this;
    }

    public Rule jumpChainId(UUID jumpChainId) {
        principalDto.setJumpChainId(jumpChainId);
        return this;
    }

    public Rule matchForwardFlow(boolean matchForwardFlow) {
        principalDto.setMatchForwardFlow(matchForwardFlow);
        return this;
    }

    public Rule nwSrcLength(int nwSrcLength) {
        principalDto.setNwSrcLength(nwSrcLength);
        return this;
    }

    public Rule flowAction(String flowAction) {
        principalDto.setFlowAction(flowAction);
        return this;
    }

    public Rule condInvert(boolean condInvert) {
        principalDto.setCondInvert(condInvert);
        return this;
    }

    public Rule dlSrc(String dlSrc) {
        principalDto.setDlSrc(dlSrc);
        return this;
    }

    public Rule invDlSrc(boolean invDlSrc) {
        principalDto.setInvDlSrc(invDlSrc);
        return this;
    }

    public Rule nwDstLength(int nwDstLength) {
        principalDto.setNwDstLength(nwDstLength);
        return this;
    }

    public Rule invPortGroup(boolean invPortGroup) {
        principalDto.setInvPortGroup(invPortGroup);
        return this;
    }

    public Rule nwTos(int nwTos) {
        principalDto.setNwTos(nwTos);
        return this;
    }

    public Rule nwSrcAddress(String nwSrcAddress) {
        principalDto.setNwSrcAddress(nwSrcAddress);
        return this;
    }

    public Rule inPorts(UUID[] inPorts) {
        principalDto.setInPorts(inPorts);
        return this;
    }

    public Rule tpSrc(DtoRule.DtoRange<Integer> tpSrc) {
        principalDto.setTpSrc(tpSrc);
        return this;
    }

    public Rule invNwProto(boolean invNwProto) {
        principalDto.setInvNwProto(invNwProto);
        return this;
    }

    public Rule type(String type) {
        principalDto.setType(type);
        return this;
    }
}
