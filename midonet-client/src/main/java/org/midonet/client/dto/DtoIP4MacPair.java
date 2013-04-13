/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.client.dto;

import java.net.URI;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class DtoIP4MacPair {
    String ip;
    String mac;
    URI uri;

    // Default constructor needed for deserialization
    public DtoIP4MacPair() {}

    public DtoIP4MacPair(String ip, String mac) {
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

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DtoIP4MacPair that = (DtoIP4MacPair) o;

        if (ip != null ? !ip.equals(that.ip) : that.ip != null) return false;
        if (mac != null ? !mac.equals(that.mac) : that.mac != null)
            return false;
        if (uri != null ? !uri.equals(that.uri) : that.uri != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = ip != null ? ip.hashCode() : 0;
        result = 31 * result + (mac != null ? mac.hashCode() : 0);
        result = 31 * result + (uri != null ? uri.hashCode() : 0);
        return result;
    }
}
