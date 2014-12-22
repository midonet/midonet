/*
 * Copyright 2014, Midokura SARL
 * Originally created by David Erickson, Stanford University
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

package org.midonet.packets;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 *
 * @author David Erickson (daviderickson@cs.stanford.edu)
 */
public class DHCPOption {

    public static final Map<Byte, String> codeToName
            = new HashMap<Byte, String>();
    // The list of DHCP options. The naming convention follows the rule below:
    //   1. An enum name is a DHCP Option name capitalized and replaced its
    //      spaces and slashes with underscores.
    //   2. A "name" of an enum follows the same rule above except for rule 3.
    //   3. If an DHCP option name contains parentheses and abbreviations, i.e.,
    //      SMTP and POP3, a corresponding enum name uses them instead of long
    //      names. However a value of an enum takes the longer names instead of
    //      the abbreviations.
    // See the following specs for more details:
    //   https://tools.ietf.org/html/rfc2132
    //   https://tools.ietf.org/html/rfc3315
    //   https://tools.ietf.org/html/rfc4361
    //   https://tools.ietf.org/html/rfc6842
    public static enum Code {
        // RFC1497 Vendor extensions
        PAD((byte) 0, (byte) 0, "PAD"),
        MASK((byte) 1, (byte) 4, "SUBNET MASK"),
        TIME_OFFSET((byte) 2, (byte) 4, "TIME OFFSET"),
        ROUTER((byte) 3, (byte) 4, "ROUTER"),
        TIME_SERVER((byte) 4, (byte) 4, "TIME_SERVER"),
        NAME_SERVER((byte) 5, (byte) 4, "NAME_SERVER"),
        DNS((byte) 6, (byte) 4, "DNS"),
        LOG_SERVER((byte) 7, (byte) 4, "LOG_SERVER"),
        COOKIE_SERVER((byte) 8, (byte) 4, "COOKIE_SERVER"),
        LPR_SERVER((byte) 9, (byte) 4, "LPR_SERVER"),
        IMPRESS_SERVER((byte) 10, (byte) 4, "IMPRESS_SERVER"),
        RESOURCE_LOCATION_SERVER(
            (byte) 11, (byte) 4, "RESOURCE_LOCATION_SERVER"),
        HOST_NAME((byte) 12, (byte) 1, "HOST NAME"),
        BOOT_FILE_SIZE((byte) 13, (byte) 2, "BOOT_FILE_SIZE"),
        MERIT_DUMP_FILE((byte) 14, (byte) 1, "MERIT_DUMP_FILE"),
        DOMAIN_NAME((byte) 15, (byte) 1, "DOMAIN NAME"),
        SWAP_SERVER((byte) 16, (byte) 4, "SWAP_SERVER"),
        ROOT_PATH((byte) 17, (byte) 1, "ROOT_PATH"),
        EXTENSION_PATH((byte) 18, (byte) 1, "EXTENSION_PATH"),
        END((byte) 255, (byte) 0, "END"),
        // IP Layer Parameters per Host
        IP_FOWARDING_ENABLE_DISABLE(
            (byte) 19, (byte) 1, "IP_FOWARDING_ENABLE_DISABLE"),
        NON_LOCAL_SOURCE_ROUTING_ENABLE_DISABLE(
            (byte) 20, (byte) 1, "NON_LOCAL_SOURCE_ROUTING_ENABLE_DISABLE"),
        POLICY_FILTER((byte) 21, (byte) 8, "POLICY_FILTER"),
        MAXIMUM_DATAGRAM_REASSEMBLY_SIZE(
            (byte) 22, (byte) 2, "MAXIMUM_DATAGRAM_REASSEMBLY_SIZE"),
        DEFAULT_IP_TTL((byte) 23, (byte) 1, "DEFAULT_IP_TTL"),
        PATH_MTU_AGING_TIMEOUT((byte) 24, (byte) 4, "PATH_MTU_AGING_TIMEOUT"),
        PATH_MTU_PLATEAU_TABLE((byte) 25, (byte) 2, "PATH_MTU_PLATEAU_TABLE"),
        // IP Layer Paremeters per Interface
        INTERFACE_MTU((byte) 26, (byte) 2, "INTERFACE MTU"),
        ALL_SUBNETS_ARE_LOCAL((byte) 27, (byte) 1, "ALL_SUBNETS_ARE_LOCAL"),
        BCAST_ADDR((byte) 28, (byte) 4, "BROADCAST ADDRESS"),
        PERFORM_MASK_DISCOVERY((byte) 29, (byte) 1, "PERFORM_MASK_DISCOVERY"),
        MASK_SUPPLIER((byte) 30, (byte) 1, "MASK_SUPPLIER"),
        PERFORME_ROUTER_DISCOVERY(
            (byte) 31, (byte) 1, "PERFORME_ROUTER_DISCOVERY"),
        ROUTER_SOLITATION_ADDRESS(
            (byte) 32, (byte) 4, "ROUTER_SOLITATION_ADDRESS"),
        STATIC_ROUTE((byte) 33, (byte) 8, "STATIC_ROUTE"),
        // Link Layer Parameters per Interface
        TRAILER_ENCAPSULATION_OPTION(
            (byte) 34, (byte) 1, "TRAILER_ENCAPSULATION_OPTION"),
        ARP_CACHE_TIMEOUT((byte) 35, (byte) 4, "ARP_CACHE_TIMEOUT"),
        ETHERNET_ENCAPSULATION((byte) 36, (byte) 1, "ETHERNET_ENCAPSULATION"),
        // TCP Parameters
        TCP_DEFAULT_TTL((byte) 37, (byte) 1, "TCP_DEFAULT_TTL"),
        TCP_KEEPALIVE_INTERVAL((byte) 38, (byte) 4, "TCP_KEEPALIVE_INTERVAL"),
        TCP_KEEPALIVE_GARBAGE((byte) 39, (byte) 1, "TCP_KEEPALIVE_GARBAGE"),
        // Application and Service Paramerters
        NIS_DOMAIN((byte) 40, (byte) 1, "NIS DOMAIN"),
        NIS_SERVERS((byte) 41, (byte) 4, "NIS SERVERS"),
        NTP_SERVERS((byte) 42, (byte) 4, "NTP SERVERS"),
        VENDOR_SPECIFIC_INFORMATION(
            (byte) 43, (byte) 1, "VENDOR_SPECIFIC_INFORMATION"),
        NETBIOS_OVER_TCP_IP_NAME_SERVER(
            (byte) 44, (byte) 4, "NETBIOS_OVER_TCP_IP_NAME_SERVER"),
        NETBIOS_OVER_TCP_IP_DATAGRAM_DISTRIBUTION_SERVER(
            (byte) 45, (byte) 4,
            "NETBIOS_OVER_TCP_IP_DATAGRAM_DISTRIBUTION_SERVER"),
        NETBIOS_OVER_TCP_IP_NODE_TYPE(
            (byte) 46, (byte) 1, "NETBIOS_OVER_TCP_IP_NODE_TYPE"),
        NETBIOS_OVER_TCP_IP_SCOPE(
            (byte) 47, (byte) 1, "NETBIOS_OVER_TCP_IP_SCOPE"),
        X_WINDOW_SYSTEM_FONT_SRVER(
            (byte) 48, (byte) 4, "X_WINDOW_SYSTEM_FONT_SRVER"),
        X_WINDOW_SYSTEM_DISPLAY_MANGER(
            (byte) 49, (byte) 4, "X_WINDOW_SYSTEM_DISPLAY_MANGER"),
        NETWORK_INFORMATION_SREVICE_AND_DOMAIN(
            (byte) 64, (byte) 1, "NETWORK_INFORMATION_SREVICE_AND_DOMAIN"),
        NETWORK_INFORMATION_SREVICE_AND_SERVERS(
            (byte) 65, (byte) 4, "NETWORK_INFORMATION_SREVICE_AND_SERVERS"),
        MOBILE_IP_HOME_AGENT((byte) 68, (byte) 4, "MOBILE_IP_HOME_AGENT"),
        SMTP_SERVER(
            (byte) 69, (byte) 4, "SIMPLE_MAIL_TRANSPORT_PROTOCOL_SERVER"),
        POP3_SERVER((byte) 70, (byte) 4, "POST_OFFICE_PROTOCOL_SERVER"),
        NNTP_SERVER(
            (byte) 71, (byte) 4, "NETWORK_NEWS_TRANSPORT_PROTOCOL_SERVER"),
        DEFAULT_WWW_SERVER(
            (byte) 72, (byte) 4, "Default World Wide Web Server"),
        DEFAULT_FINGER_SERVER((byte) 73, (byte) 4, "DEFAULT_FINGER_SERVER"),
        DEFAULT_IRC_SERVER(
            (byte) 74, (byte) 4, "DEFAULT_INTERNET_RELAY_CHAT_SERVER"),
        STREETTALK_SERVER(
            (byte) 75, (byte) 4, "STREETTALK_SERVER"),
        STDA_SERVER(
            (byte) 76, (byte) 4, "STREETTALK_DIRECTORY_ASSISTANCE_SERVER"),
        // DHCP Extensions
        REQUESTED_IP((byte) 50, (byte) 4, "REQUESTED IP"),
        IP_LEASE_TIME((byte) 51, (byte) 4, "IP LEASE TIME"),
        OPTION_OVERLOAD((byte) 52, (byte) 1, "OPTION OVERLOAD"),
        DHCP_TYPE((byte) 53, (byte) 1, "DHCP MESSAGE TYPE"),
        SERVER_ID((byte) 54, (byte) 4, "SERVER ID"),
        PRM_REQ_LIST((byte) 55, (byte) 1, "PARAMETER REQUEST LIST"),
        ERROR_MESSAGE((byte) 56, (byte) 1, "ERROR MESSAGE"),
        MESSAGE((byte) 56, (byte) 1, "MESSAGE"),
        MAXIMUM_DHCP_MESSAGE_SIZE(
            (byte) 57, (byte) 2, "MAXIMUM_DHCP_MESSAGE_SIZE"),
        RENEWAL_TIME_VALUE((byte) 58, (byte) 4, "RENEWAL_TIME_VALUE"),
        REBINDING_TIME_VALUE((byte) 59, (byte) 4, "REBINDING_TIME_VALUE"),
        VENDOR_CLASS_IDENTIFIER((byte) 60, (byte) 1, "VENDOR_CLASS_IDENTIFIER"),
        CLIENT_IDENTIFIER((byte) 61, (byte) 1, "CLIENT_IDENTIFIER"),
        TFTP_SERVER_NAME((byte) 66, (byte) 1, "TFTP_SERVER_NAME"),
        BOOTFILE_NAME((byte) 67, (byte) 1, "BOOTFILE_NAME"),
        // Others
        //   https://tools.ietf.org/html/rfc3442
        CLASSLESS_ROUTES((byte) 121, (byte) 5, "CLASSLESS STATIC ROUTES");

        private final byte code;
        private final byte length;
        private final String name;

        Code(byte code, byte length, String name) {
            this.code = code;
            this.length = length;
            this.name = name;
            codeToName.put(code, name);
        }

        public byte value() {
            return code;
        }

        public byte length() {
            return length;
        }

        public String getName() {
            return name;
        }
    }

    public static final Code[] IP_REQUIRED_DHCP_OPTION_CODES = {
            Code.MASK,
            Code.ROUTER,
            Code.TIME_SERVER,
            Code.NAME_SERVER,
            Code.DNS,
            Code.LOG_SERVER,
            Code.COOKIE_SERVER,
            Code.LPR_SERVER,
            Code.IMPRESS_SERVER,
            Code.RESOURCE_LOCATION_SERVER,
            Code.SWAP_SERVER,
            Code.BCAST_ADDR,
            Code.ROUTER_SOLITATION_ADDRESS,
            Code.STATIC_ROUTE,
            Code.NIS_SERVERS,
            Code.NTP_SERVERS,
            Code.NETBIOS_OVER_TCP_IP_NAME_SERVER,
            Code.NETBIOS_OVER_TCP_IP_DATAGRAM_DISTRIBUTION_SERVER,
            Code.NETBIOS_OVER_TCP_IP_NODE_TYPE,
            Code.X_WINDOW_SYSTEM_FONT_SRVER,
            Code.X_WINDOW_SYSTEM_DISPLAY_MANGER,
            Code.NETWORK_INFORMATION_SREVICE_AND_SERVERS,
            Code.MOBILE_IP_HOME_AGENT,
            Code.SMTP_SERVER,
            Code.POP3_SERVER,
            Code.NNTP_SERVER,
            Code.DEFAULT_WWW_SERVER,
            Code.DEFAULT_FINGER_SERVER,
            Code.DEFAULT_IRC_SERVER,
            Code.STREETTALK_SERVER,
            Code.STDA_SERVER,
            Code.REQUESTED_IP,
            Code.SERVER_ID,
            Code.CLASSLESS_ROUTES
    };

    public static final Code[] NUMBER_REQUIRED_DHCP_OPTION_CODES = {
            Code.TIME_OFFSET,
            Code.BOOT_FILE_SIZE,
            Code.MERIT_DUMP_FILE,
            Code.MAXIMUM_DATAGRAM_REASSEMBLY_SIZE,
            Code.DEFAULT_IP_TTL,
            Code.PATH_MTU_AGING_TIMEOUT,
            Code.PATH_MTU_PLATEAU_TABLE,
            Code.INTERFACE_MTU,
            Code.ARP_CACHE_TIMEOUT,
            Code.TCP_KEEPALIVE_INTERVAL,
            Code.IP_LEASE_TIME,
            Code.MAXIMUM_DHCP_MESSAGE_SIZE,
            Code.RENEWAL_TIME_VALUE,
            Code.REBINDING_TIME_VALUE
    };

    public static final Code[] BOOLEAN_REQUIRED_DHCP_OPTION_CODES = {
            Code.IP_FOWARDING_ENABLE_DISABLE,
            Code.NON_LOCAL_SOURCE_ROUTING_ENABLE_DISABLE,
            Code.ALL_SUBNETS_ARE_LOCAL,
            Code.PERFORM_MASK_DISCOVERY,
            Code.MASK_SUPPLIER,
            Code.PERFORM_MASK_DISCOVERY,
            Code.TRAILER_ENCAPSULATION_OPTION,
            Code.ETHERNET_ENCAPSULATION,
            Code.TCP_KEEPALIVE_GARBAGE
    };

    public static final Code[] CIDR_REQUIRED_DHCP_OPTION_CODES = {
            Code.POLICY_FILTER,
            Code.STATIC_ROUTE
    };

    public static final Map<Byte, String> msgTypeToName
            = new HashMap<Byte, String>();
    public static enum MsgType {
        DISCOVER((byte)1, "DISCOVER"),
        OFFER((byte)2, "OFFER"),
        REQUEST((byte)3, "REQUEST"),
        DECLINE((byte)4, "DECLINE"),
        ACK((byte)5, "ACK"),
        NAK((byte)6, "NAK"),
        RELEASE((byte)7, "RELEASE");

        private final byte value;
        private final String name;

        MsgType(byte value, String name) {
            this.value = value;
            this.name = name;
            msgTypeToName.put(value, name);
        }

        public byte value() {
            return value;
        }

        public String getName() {
            return name;
        }
    }

    protected byte code;
    protected byte length;
    protected byte[] data;

    public DHCPOption() {}

    public DHCPOption(byte code, byte length, byte[] data) {
        this.code = code;
        this.length = length;
        this.data = data;
    }

    /**
     * @return the code
     */
    public byte getCode() {
        return code;
    }

    /**
     * @param code the code to set
     */
    public DHCPOption setCode(byte code) {
        this.code = code;
        return this;
    }

    /**
     * @return the length
     */
    public byte getLength() {
        return length;
    }

    /**
     * @param length the length to set
     */
    public DHCPOption setLength(byte length) {
        this.length = length;
        return this;
    }

    /**
     * @return the data
     */
    public byte[] getData() {
        return data;
    }

    /**
     * @param data the data to set
     */
    public DHCPOption setData(byte[] data) {
        this.data = data;
        return this;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + code;
        result = prime * result + Arrays.hashCode(data);
        result = prime * result + length;
        return result;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (!(obj instanceof DHCPOption))
            return false;
        DHCPOption other = (DHCPOption) obj;
        if (code != other.code)
            return false;
        if (!Arrays.equals(data, other.data))
            return false;
        if (length != other.length)
            return false;
        return true;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "DHCPOption [code=" + code + ", length=" + length + ", data="
                + Arrays.toString(data) + "]";
    }
}
