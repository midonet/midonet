/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.api.network;

import java.net.URI;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.xml.bind.annotation.XmlRootElement;

import org.midonet.api.RelativeUriResource;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.util.StringUtil;

@XmlRootElement
public class IP4MacPair extends RelativeUriResource {

    @NotNull
    @Pattern(regexp = StringUtil.IP_ADDRESS_REGEX_PATTERN)
    private String ip;

    @NotNull
    @Pattern(regexp = StringUtil.MAC_ADDRESS_REGEX_PATTERN)
    protected String macAddr;

    /* Default constructor - for deserialization. */
    public IP4MacPair() {}

    public IP4MacPair(String ip, String macAddr) {
        this.ip = ip;
        this.macAddr = macAddr;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getMac() {
        return macAddr;
    }

    public void setMac(String macAddr) {
        this.macAddr = macAddr;
    }

    @Override
    public URI getUri() {
        if (getParentUri() != null && macAddr != null && ip != null) {
            return ResourceUriBuilder.getIP4MacPair(getParentUri(), this);
        } else {
            return null;
        }
    }
}
