/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.client.dto;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@XmlRootElement
public class DtoTraceCondition {
    private URI uri;
    private UUID id;
    private boolean condInvert;
    private boolean matchForwardFlow;
    private boolean matchReturnFlow;
    private UUID[] inPorts;
    private boolean invInPorts;
    private UUID[] outPorts;
    private boolean invOutPorts;
    private UUID portGroup;
    private boolean invPortGroup;
    private Integer dlType = null;
    private boolean invDlType = false;
    private String dlSrc = null;
    private boolean invDlSrc = false;
    private String dlDst = null;
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
    private DtoRange<Integer> tpSrc;
    private boolean invTpSrc;
    private DtoRange<Integer> tpDst;
    private boolean invTpDst;

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

    public String getDlDst() {
        return dlDst;
    }

    public void setDlDst(String dlDst) {
        this.dlDst = dlDst;
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

    public static class DtoRange<E> {
        public E start;
        public E end;

        public DtoRange() {
        }

        public DtoRange(E start_, E end_) {
            this.start = start_;
            this.end = end_;
        }

        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            @SuppressWarnings("unchecked") // safe cast at that point
            DtoRange<E> that = (DtoRange<E>) o;
            if (start != null ? !start.equals(that.start) : that.start != null ||
                end != null ? !end.equals(that.end) : that.end != null)
                return false;

            return true;
        }
    }

    /**
     * Check if arrays contain the same elements. The elements do not have to be
     * in corresponding positions. This method doesn't re-arrange the elements
     *
     * @param a1 Array to compare
     * @param a2 Array to compare
     * @return true if array contents is equal
     */
    private boolean arraysEqual(Object[] a1, Object[] a2) {
        if (a1 == a2 || a1 == null && a2.length == 0 ||
            a2 == null && a1.length == 0)
            return true;

        if (a1 == null || a2 == null || a1.length != a2.length)
            return false;

        for (Object e1: a1) {
            boolean found = false;
            for (Object e2: a2) {
                if (e1 == e2 || e1 != null && e1.equals(e2)) {
                    found = true;
                    break;
                }
            }
            if (!found)
                return false;
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;

        DtoTraceCondition that = (DtoTraceCondition) o;

        if ((uri != null ? !uri.equals(that.uri) : that.uri != null) ||
            (id != null ? !id.equals(that.id) : that.id != null) ||
            (condInvert != that.condInvert) ||
            (matchForwardFlow != that.matchForwardFlow) ||
            (matchReturnFlow != that.matchReturnFlow) ||
            (invInPorts != that.invInPorts) ||
            (invOutPorts != that.invOutPorts) ||
            (portGroup != null ? !portGroup.equals(that.portGroup) :
                that.portGroup != null) ||
            (invPortGroup != that.invPortGroup) ||
            (dlType != null ? !dlType.equals(that.dlType) : that.dlType != null) ||
            (invDlType != that.invDlType) ||
            (dlSrc != null ? !dlSrc.equals(that.dlSrc) : that.dlSrc != null) ||
            (invDlSrc != that.invDlSrc) ||
            (dlDst != null ? !dlDst.equals(that.dlDst) : that.dlDst != null) ||
            (invDlDst != that.invDlDst) ||
            (nwTos != that.nwTos) ||
            (invNwTos != that.invNwTos) ||
            (nwProto != that.nwProto) ||
            (invNwProto != that.invNwProto) ||
            (nwSrcAddress != null ? !nwSrcAddress.equals(that.nwSrcAddress) :
                that.nwSrcAddress != null) ||
            (nwSrcLength != that.nwSrcLength) ||
            (invNwSrc != that.invNwSrc) ||
            (nwDstAddress != null ? !nwDstAddress.equals(that.nwDstAddress) :
                that.nwDstAddress != null) ||
            (nwDstLength != that.nwDstLength) ||
            (invNwDst != that.invNwDst) ||
            (tpSrc != null ? !tpSrc.equals(that.tpSrc) : that.tpSrc != null) ||
            (invTpSrc != that.invTpSrc) ||
            (tpDst != null ? !tpDst.equals(that.tpDst) : that.tpDst != null) ||
            (invTpDst != that.invTpDst))
            return false;

        if (!arraysEqual(inPorts, that.inPorts) ||
            !arraysEqual(outPorts, that.outPorts))
            return false;

        return true;
    }
}
