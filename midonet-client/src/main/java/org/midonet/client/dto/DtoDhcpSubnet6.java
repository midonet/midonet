/*
 * Copyright 2013 Midokura Europe SARL
 */

package org.midonet.client.dto;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@XmlRootElement
public class DtoDhcpSubnet6 {
    private String prefix;
    private int prefixLength;
    private URI hosts;
    private URI uri;

    public DtoDhcpSubnet6() {
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public int getPrefixLength() {
        return prefixLength;
    }

    public void setPrefixLength(int prefixLength) {
        this.prefixLength = prefixLength;
    }

    public URI getHosts() {
        return hosts;
    }

    public void setHosts(URI hosts) {
        this.hosts = hosts;
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

        DtoDhcpSubnet6 that = (DtoDhcpSubnet6) o;

        if (prefixLength != that.prefixLength) return false;
        if (hosts != null
                ? !hosts.equals(that.hosts)
                : that.hosts != null)
            return false;
        if (prefix != null
                ? !prefix.equals(that.prefix)
                : that.prefix != null)
            return false;
        if (uri != null
                ? !uri.equals(that.uri)
                : that.uri != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = prefix != null ? prefix.hashCode() : 0;
        result = 31 * result + prefixLength;
        result = 31 * result + (hosts != null ? hosts.hashCode() : 0);
        result = 31 * result + (uri != null ? uri.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "DtoDhcpSubnet6{" +
                "prefix='" + prefix + '\'' +
                ", prefixLength=" + prefixLength + '\'' +
                ", hosts=" + hosts +
                ", uri=" + uri +
                '}';
    }
}
