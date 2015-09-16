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

@XmlRootElement
public class DtoDhcpOption121 {
    protected String destinationPrefix;
    protected int destinationLength;
    protected String gatewayAddr;

    /*  Default constructor for parsing. */
    public DtoDhcpOption121() {
    }

    public DtoDhcpOption121(String destinationPrefix, int destinationLength,
                            String gatewayAddr) {
        this.destinationPrefix = destinationPrefix;
        this.destinationLength = destinationLength;
        this.gatewayAddr = gatewayAddr;
    }

    public String getDestinationPrefix() {
        return destinationPrefix;
    }

    public void setDestinationPrefix(String destinationPrefix) {
        this.destinationPrefix = destinationPrefix;
    }

    public int getDestinationLength() {
        return destinationLength;
    }

    public void setDestinationLength(int destinationLength) {
        this.destinationLength = destinationLength;
    }

    public String getGatewayAddr() {
        return gatewayAddr;
    }

    public void setGatewayAddr(String gatewayAddr) {
        this.gatewayAddr = gatewayAddr;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DtoDhcpOption121 that = (DtoDhcpOption121) o;

        if (destinationLength != that.destinationLength) return false;
        if (destinationPrefix != null
                ? !destinationPrefix.equals(that.destinationPrefix)
                : that.destinationPrefix != null)
            return false;
        if (gatewayAddr != null
                ? !gatewayAddr.equals(that.gatewayAddr)
                : that.gatewayAddr != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = destinationPrefix != null
                ? destinationPrefix.hashCode() : 0;
        result = 31 * result + destinationLength;
        result = 31 * result + (gatewayAddr != null
                ? gatewayAddr.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "DtoDhcpOption121{" +
                "destinationLength=" + destinationLength +
                ", destinationPrefix='" + destinationPrefix + '\'' +
                ", gatewayAddr='" + gatewayAddr + '\'' +
                '}';
    }
}
