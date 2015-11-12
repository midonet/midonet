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

package org.midonet.client.dto;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@XmlRootElement
public class DtoRule {
    public static final String Accept = "accept";
    public static final String Continue = "continue";
    public static final String Drop = "drop";
    public static final String Jump = "jump";
    public static final String L2Transform = "l2_transform";
    public static final String Redirect = "redirect";
    public static final String Reject = "reject";
    public static final String Return = "return";
    public static final String DNAT = "dnat";
    public static final String SNAT = "snat";
    public static final String RevDNAT = "rev_dnat";
    public static final String RevSNAT = "rev_snat";

    private URI uri;
    private UUID id;
    private UUID chainId;
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
    private UUID traversedDevice;
    private boolean invTraversedDevice;
    private Integer dlType = null;
    private boolean invDlType = false;
    private String dlSrc = null;
    private String dlSrcMask = null;
    private boolean invDlSrc = false;
    private String dlDst = null;
    private String dlDstMask = null;
    private boolean invDlDst = false;
    private int nwTos;
    private boolean invNwTos;
    private int nwProto;
    private boolean invNwProto;
    private String nwSrcAddress;
    private int nwSrcLength;
    private boolean invNwSrc;
    private String nwDstAddress;
    private int nwDstLength;
    private boolean invNwDst;
    private String fragmentPolicy;
    private DtoRange<Integer> tpSrc;
    private boolean invTpSrc;
    private DtoRange<Integer> tpDst;
    private boolean invTpDst;
    private String type;
    private String jumpChainName;
    private UUID jumpChainId;
    private String flowAction;
    private DtoNatTarget[] natTargets;
    private int position;
    private String meterName;
    private boolean popVlan;
    private int pushVlan;
    private UUID targetPortId;
    private boolean ingress;
    private boolean failOpen;
    private Map<String, String> properties = new HashMap<String, String>();

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getChainId() {
        return chainId;
    }

    public void setChainId(UUID chainId) {
        this.chainId = chainId;
    }

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

    public UUID getTraversedDevice() {
        return traversedDevice;
    }

    public void setIpAddrGroupDst(UUID ipAddrGroupDst) {
        this.ipAddrGroupDst = ipAddrGroupDst;
    }

    public void setTraversedDevice(UUID device) {
        this.traversedDevice = device;
    }

    public boolean isInvIpAddrGroupDst() {
        return invIpAddrGroupDst;
    }

    public boolean isInvTraversedDevice() {
        return invTraversedDevice;
    }

    public void setInvIpAddrGroupDst(boolean invIpAddrGroupDst) {
        this.invIpAddrGroupDst = invIpAddrGroupDst;
    }

    public void setInvTraversedDevice(boolean inv) {
        this.invTraversedDevice = inv;
    }

    public String getDlDst() {
        return dlDst;
    }

    public void setDlDst(String dlDst) {
        this.dlDst = dlDst;
    }

    public String getDlDstMask() {
        return dlDstMask;
    }

    public void setDlDstMask(String dlDstMask) {
        this.dlDstMask = dlDstMask;
    }

    public boolean isInvDlDst() {
        return invDlDst;
    }

    public void setInvDlDst(boolean invDlDst) {
        this.invDlDst = invDlDst;
    }

    public String getDlSrc() {
        return dlSrc;
    }

    public void setDlSrc(String dlSrc) {
        this.dlSrc = dlSrc;
    }

    public String getDlSrcMask() {
        return dlSrcMask;
    }

    public void setDlSrcMask(String dlSrcMask) {
        this.dlSrcMask = dlSrcMask;
    }

    public boolean isInvDlSrc() {
        return invDlSrc;
    }

    public void setInvDlSrc(boolean invDlSrc) {
        this.invDlSrc = invDlSrc;
    }

    public Integer getDlType() {
        return dlType;
    }

    public void setDlType(Integer dlType) {
        Integer intDlType = null;
        if (dlType != null) {
            intDlType = dlType & 0xffff;

            if (intDlType < 0x600 || intDlType > 0xFFFF) {
                throw new IllegalArgumentException("EtherType must be in the " +
                        "range 0x0600 to 0xFFFF.");
            }
        }
        this.dlType = intDlType;
    }

    public boolean isInvDlType() {
        return invDlType;
    }

    public void setInvDlType(boolean invDlType) {
        this.invDlType = invDlType;
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

    public void setInvNwTos(boolean invNwTos) {
        this.invNwTos = invNwTos;
    }

    public int getNwProto() {
        return nwProto;
    }

    public void setNwProto(int nwProto) {
        this.nwProto = nwProto;
    }

    public boolean isInvNwProto() {
        return invNwProto;
    }

    public void setInvNwProto(boolean invNwProto) {
        this.invNwProto = invNwProto;
    }

    public String getNwSrcAddress() {
        return nwSrcAddress;
    }

    public void setNwSrcAddress(String nwSrcAddress) {
        this.nwSrcAddress = nwSrcAddress;
    }

    public int getNwSrcLength() {
        return nwSrcLength;
    }

    public void setNwSrcLength(int nwSrcLength) {
        this.nwSrcLength = nwSrcLength;
    }

    public boolean isInvNwSrc() {
        return invNwSrc;
    }

    public void setInvNwSrc(boolean invNwSrc) {
        this.invNwSrc = invNwSrc;
    }

    public String getNwDstAddress() {
        return nwDstAddress;
    }

    public void setNwDstAddress(String nwDstAddress) {
        this.nwDstAddress = nwDstAddress;
    }

    public int getNwDstLength() {
        return nwDstLength;
    }

    public void setNwDstLength(int nwDstLength) {
        this.nwDstLength = nwDstLength;
    }

    public boolean isInvNwDst() {
        return invNwDst;
    }

    public void setInvNwDst(boolean invNwDst) {
        this.invNwDst = invNwDst;
    }

    public String getFragmentPolicy() {
        return fragmentPolicy;
    }

    public void setFragmentPolicy(String fragmentPolicy) {
        this.fragmentPolicy = fragmentPolicy;
    }

    public boolean isInvTpSrc() {
        return invTpSrc;
    }

    public void setInvTpSrc(boolean invTpSrc) {
        this.invTpSrc = invTpSrc;
    }

    public DtoRange<Integer> getTpSrc() {
        return tpSrc;
    }

    public DtoRange<Integer> getTpDst() {
        return tpDst;
    }

    public void setTpDst(DtoRange<Integer> tpDst) {
        this.tpDst = tpDst;
    }

    public void setTpSrc(DtoRange<Integer> tpSrc) {
        this.tpSrc = tpSrc;
    }

    public boolean isInvTpDst() {
        return invTpDst;
    }

    public void setInvTpDst(boolean invTpDst) {
        this.invTpDst = invTpDst;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getJumpChainName() {
        return jumpChainName;
    }

    public void setJumpChainName(String jumpChainName) {
        this.jumpChainName = jumpChainName;
    }

    public UUID getJumpChainId() {
        return jumpChainId;
    }

    public void setJumpChainId(UUID jumpChainId) {
        this.jumpChainId = jumpChainId;
    }

    public String getFlowAction() {
        return flowAction;
    }

    public void setFlowAction(String flowAction) {
        this.flowAction = flowAction;
    }

    public DtoNatTarget[] getNatTargets() {
        return natTargets;
    }

    public void setNatTargets(DtoNatTarget[] natTargets) {
        this.natTargets = natTargets;
    }

    public int getPosition() {
        return position;
    }

    public String getMeterName() {
        return meterName;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public void setMeterName(String meterName) {
        this.meterName = meterName;
    }

    public boolean getPopVlan() {
        return popVlan;
    }

    public void setPopVlan(boolean popVlan) {
        this.popVlan = popVlan;
    }

    public int getPushVlan() {
        return pushVlan;
    }

    public void setPushVlan(int pushVlan) {
        this.pushVlan = pushVlan;
    }

    public UUID getTargetPortId() {
        return targetPortId;
    }

    public void setTargetPortId(UUID targetPortId) {
        this.targetPortId = targetPortId;
    }

    public boolean getIngress() {
        return ingress;
    }

    public void setIngress(boolean ingress) {
        this.ingress = ingress;
    }

    public boolean getFailOpen() {
        return failOpen;
    }

    public void setFailOpen(boolean failOpen) {
        this.failOpen = failOpen;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }

    public static class DtoNatTarget {
        public String addressFrom, addressTo;
        public int portFrom, portTo;

        public DtoNatTarget() {
        }

        public DtoNatTarget(String addressFrom, String addressTo, int portFrom,
                            int portTo) {
            this.addressFrom = addressFrom;
            this.addressTo = addressTo;
            this.portFrom = portFrom;
            this.portTo = portTo;
        }
    }

    public static class DtoRange<E> {
        public E start;
        public E end;

        public DtoRange() {
        }

        public DtoRange(E start_, E end_) {
            this.start = start_;
            this.end = end_;
        }

    }
}
