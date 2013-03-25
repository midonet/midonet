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
    protected String mac;

    /* Default constructor - for deserialization. */
    public IP4MacPair() {}

    public IP4MacPair(String ip, String mac) {
        this.ip = ip;
        this.mac = mac;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getMac() {
        return mac;
    }

    public void setMac(String mac) {
        this.mac = mac;
    }

    @Override
    public URI getUri() {
        if (getParentUri() != null && mac != null && ip != null) {
            return ResourceUriBuilder.getIP4MacPair(getParentUri(), this);
        } else {
            return null;
        }
    }
}
