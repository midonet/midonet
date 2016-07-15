/*
 * Copyright 2011, Big Switch Networks, Inc.
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

    public static final Map<Byte, String> CODE_TO_NAME = new HashMap<>();
    public static final Map<String, Byte> NAME_TO_CODE = new HashMap<>();
    public static final Map<Byte, Code> CODE_TO_OPTION = new HashMap<>();
    // The list of DHCP options. The naming convention follows the rule below:
    //   1. An enum name is a DHCP Option name capitalized and replaced its
    //      spaces and slashes with underscores.
    //   2. A "name" of an enum follows the same rule above except for rule 3.
    //   3. If an DHCP option name contains parentheses and abbreviations, i.e.,
    //      SMTP and POP3, a corresponding enum name uses them instead of long
    //      names. However a value of an enum takes the longer names instead of
    //      the abbreviations.
    // See the specs in the following link for more details:
    //   http://www.iana.org/assignments/bootp-dhcp-parameters/bootp-dhcp-parameters.xhtml#options
    public static enum Code {
        // RFC1497 Vendor extensions
        PAD((byte) 0, (byte) 0, "PAD"),
        MASK((byte) 1, (byte) 4, "SUBNET_MASK"),
        TIME_OFFSET((byte) 2, (byte) 4, "TIME_OFFSET"),
        ROUTER((byte) 3, (byte) 4, "ROUTER"),
        TIME_SERVER((byte) 4, (byte) 4, "TIME_SERVER"),
        NAME_SERVER((byte) 5, (byte) 4, "NAME_SERVER"),
        DNS((byte) 6, (byte) 4, "DNS"),
        LOG_SERVER((byte) 7, (byte) 4, "LOG_SERVER"),
        QUOTES_SERVER((byte) 8, (byte) 4, "QUOTES_SERVER"),
        LPR_SERVER((byte) 9, (byte) 4, "LPR_SERVER"),
        IMPRESS_SERVER((byte) 10, (byte) 4, "IMPRESS_SERVER"),
        RESOURCE_LOCATION_SERVER(
            (byte) 11, (byte) 4, "RESOURCE_LOCATION_SERVER"),
        HOST_NAME((byte) 12, (byte) 1, "HOST_NAME"),
        BOOT_FILE_SIZE((byte) 13, (byte) 2, "BOOT_FILE_SIZE"),
        MERIT_DUMP_FILE((byte) 14, (byte) 1, "MERIT_DUMP_FILE"),
        DOMAIN_NAME((byte) 15, (byte) 1, "DOMAIN NAME"),
        SWAP_SERVER((byte) 16, (byte) 4, "SWAP_SERVER"),
        ROOT_PATH((byte) 17, (byte) 1, "ROOT_PATH"),
        EXTENSION_PATH((byte) 18, (byte) 1, "EXTENSION_PATH"),
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
        INTERFACE_MTU((byte) 26, (byte) 2, "INTERFACE_MTU"),
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
        NIS_DOMAIN((byte) 40, (byte) 1, "NIS_DOMAIN"),
        NIS_SERVERS((byte) 41, (byte) 4, "NIS_SERVERS"),
        NTP_SERVERS((byte) 42, (byte) 4, "NTP_SERVERS"),
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

        // DHCP Extensions
        REQUESTED_IP((byte) 50, (byte) 4, "REQUESTED_IP"),
        IP_LEASE_TIME((byte) 51, (byte) 4, "IP_LEASE_TIME"),
        OPTION_OVERLOAD((byte) 52, (byte) 1, "OPTION_OVERLOAD"),
        DHCP_TYPE((byte) 53, (byte) 1, "DHCP_MESSAGE_TYPE"),
        SERVER_ID((byte) 54, (byte) 4, "SERVER_ID"),
        PRM_REQ_LIST((byte) 55, (byte) 1, "PARAMETER_REQUEST_LIST"),
        ERROR_MESSAGE((byte) 56, (byte) 1, "ERROR_MESSAGE"),
        MAXIMUM_DHCP_MESSAGE_SIZE(
            (byte) 57, (byte) 2, "MAXIMUM_DHCP_MESSAGE_SIZE"),
        RENEWAL_TIME_VALUE((byte) 58, (byte) 4, "RENEWAL_TIME_VALUE"),
        REBINDING_TIME_VALUE((byte) 59, (byte) 4, "REBINDING_TIME_VALUE"),
        CLASS_IDENTIFIER((byte) 60, (byte) 1, "CLASS_IDENTIFIER"),
        CLIENT_IDENTIFIER((byte) 61, (byte) 1, "CLIENT_IDENTIFIER"),
        // http://tools.ietf.org/html/rfc2242
        NWIP_DOMAIN((byte) 62, (byte) 1, "NWIP_DOMAIN"),
        NWIP_SUBOPTIONS((byte) 63, (byte) 1, "NWIP_SUBOPTIONS"),
        NETWORK_INFORMATION_SREVICE_AND_DOMAIN(
            (byte) 64, (byte) 1, "NETWORK_INFORMATION_SREVICE_AND_DOMAIN"),
        NETWORK_INFORMATION_SREVICE_AND_SERVERS(
            (byte) 65, (byte) 4, "NETWORK_INFORMATION_SREVICE_AND_SERVERS"),
        TFTP_SERVER_NAME((byte) 66, (byte) 1, "TFTP_SERVER_NAME"),
        BOOTFILE_NAME((byte) 67, (byte) 1, "BOOTFILE_NAME"),
        MOBILE_IP_HOME_AGENT((byte) 68, (byte) 4, "MOBILE_IP_HOME_AGENT"),
        SMTP_SERVER(
            (byte) 69, (byte) 4, "SIMPLE_MAIL_TRANSPORT_PROTOCOL_SERVER"),
        POP3_SERVER((byte) 70, (byte) 4, "POST_OFFICE_PROTOCOL_SERVER"),
        NNTP_SERVER(
            (byte) 71, (byte) 4, "NETWORK_NEWS_TRANSPORT_PROTOCOL_SERVER"),
        DEFAULT_WWW_SERVER(
            (byte) 72, (byte) 4, "DEFAULT_WORLD_WIDE_WEB_SERVER"),
        DEFAULT_FINGER_SERVER((byte) 73, (byte) 4, "DEFAULT_FINGER_SERVER"),
        DEFAULT_IRC_SERVER(
            (byte) 74, (byte) 4, "DEFAULT_INTERNET_RELAY_CHAT_SERVER"),
        STREETTALK_SERVER(
            (byte) 75, (byte) 4, "STREETTALK_SERVER"),
        STDA_SERVER(
            (byte) 76, (byte) 4, "STREETTALK_DIRECTORY_ASSISTANCE_SERVER"),
        // https://tools.ietf.org/html/rfc3004
        USER_CLASS((byte) 77, (byte) 1, "USER_CLASS"),
        // https://tools.ietf.org/html/rfc2610
        SLP_DIRECTORY_AGENT((byte) 78, (byte) 1, "SLP_DIRECTORY_AGENT"),
        SLP_SERVICE_SCOPE((byte) 79, (byte) 1, "SLP_SERVICE_SCOPE"),
        // https://tools.ietf.org/html/rfc4039
        RAPID_COMMIT((byte) 80, (byte) 1, "RAPID_COMMIT"),
        // https://tools.ietf.org/html/rfc4702
        CLIENT_FQDN((byte) 81, (byte) 1, "CLIENT_FQDN"),
        // https://tools.ietf.org/html/rfc4171
        STORAGE_NS((byte) 83, (byte) 1, "STORAGE_NS"),
        // https://tools.ietf.org/html/rfc2241
        NDS_SERVERS((byte) 85, (byte) 4, "NDS_SERVERS"),
        NDS_TREE_NAME((byte) 86, (byte) 1, "NDS_TREE_NAME"),
        NDS_CONTEXT((byte) 87, (byte) 1, "NDS_CONTEXT"),
        // https://tools.ietf.org/html/rfc4280
        BCMS_CONTROLLLER_NAMES((byte) 88, (byte) 1, "BCMS_CONTROLLLER_NAMES"),
        BCMS_CONTROLLLER_ADDRESS((byte) 89, (byte) 1, "BCMS_CONTROLLLER_ADDRESS"),
        // https://tools.ietf.org/html/rfc3118
        DHCP_AUTH((byte) 90, (byte) 1, "DHCP_AUTH"),
        // https://tools.ietf.org/html/rfc4388
        DHCP_CLIENT_LAST_TIME((byte) 91, (byte) 4, "DHCP_CLIENT_LAST_TIME"),
        ASSOCIATED_IP((byte) 92, (byte) (byte) 4, "ASSOCIATED_IP"),
        // https://tools.ietf.org/html/rfc4578
        SYSTEM_ARCHITECTURE((byte) 93, (byte) 2, "SYSTEM_ARCHITECTURE"),
        INTERFACE_ID((byte) 94, (byte) 1, "INTERFACE_ID"),
        // https://tools.ietf.org/html/rfc3679
        LDAP_SERVERS((byte) 95, (byte) 4, "LDAP_SERVERS"),
        // https://tools.ietf.org/html/rfc4578
        MACHINE_ID((byte) 97, (byte) 1, "MACHINE_ID"),
        // https://tools.ietf.org/html/rfc2485
        USER_AUTH((byte) 98, (byte) 1, "USER_AUTH"),
        // https://tools.ietf.org/html/rfc4776
        GEOCONF_CIVIC((byte) 99, (byte) 1, "GEOCONF_CIVIC"),
        // https://tools.ietf.org/html/rfc6557
        IEEE_1003_1_TZ((byte) 100, (byte) 1, "PCODE"),
        REF_TZ_DB((byte) 101, (byte) 1, "TCODE"),
        // http://tools.ietf.org/html/rfc3679
        NETINFO_SERVER_ADDRESS((byte) 112, (byte) 1, "NETINFO_SERVER_ADDRESS"),
        NETINFO_SERVER_TAG((byte) 113, (byte) 1, "NETINFO_SERVER_TAG"),
        DEFAULT_URL((byte) 114, (byte) 1, "DEFAULT_URL"),
        // http://tools.ietf.org/html/rfc2563
        AUTO_CONFIGURE((byte) 116, (byte) 1, "AUTO_CONFIGURE"),
        // http://tools.ietf.org/html/rfc2937
        NAME_SEARCH((byte) 117, (byte) 2, "NAME_SEARCH"),
        // http://tools.ietf.org/html/rfc3011
        SUBNET_SELECTION((byte) 118, (byte) 4, "SUBNET_SELECTION"),
        // http://tools.ietf.org/html/rfc3397
        DOMAIN_SEARCH((byte) 119, (byte) 1, "DOMAIN_SEARCH"),
        // http://tools.ietf.org/html/rfc3361
        SIP_SERVERS((byte) 120, (byte) 1, "SIP_SERVERS"),
        // https://tools.ietf.org/html/rfc3442
        CLASSLESS_ROUTES((byte) 121, (byte) 1, "CLASSLESS_STATIC_ROUTES"),
        // http://tools.ietf.org/html/rfc3495
        DHCP_CCC((byte) 122, (byte) 1, "DHCP_CCC"),
        // http://tools.ietf.org/html/rfc6225
        DHCP_GEOCONF((byte) 123, (byte) 16, "DHCP_GEOCONF"),
        // http://tools.ietf.org/html/rfc3925
        VENDOR_CLASS_IDENTIFIER((byte) 124, (byte) 1, "VENDOR_CLASS_IDENTIFIER"),
        VIVSO((byte) 125, (byte) 1, "VIVSO"),
        // http://tools.ietf.org/html/rfc4578
        TFTP_SERVER((byte) 128, (byte) 4, "TFTP_SERVER"),
        PXE_VENDOR_SPECIFIC_129((byte) 129, (byte) 1, "PXE_VENDOR_SPECIFIC_129"),
        PXE_VENDOR_SPECIFIC_130((byte) 130, (byte) 1, "PXE_VENDOR_SPECIFIC_130"),
        PXE_VENDOR_SPECIFIC_131((byte) 131, (byte) 1, "PXE_VENDOR_SPECIFIC_131"),
        PXE_VENDOR_SPECIFIC_132((byte) 132, (byte) 1, "PXE_VENDOR_SPECIFIC_132"),
        PXE_VENDOR_SPECIFIC_133((byte) 133, (byte) 1, "PXE_VENDOR_SPECIFIC_133"),
        PXE_VENDOR_SPECIFIC_134((byte) 134, (byte) 1, "PXE_VENDOR_SPECIFIC_134"),
        PXE_VENDOR_SPECIFIC_135((byte) 135, (byte) 1, "PXE_VENDOR_SPECIFIC_135"),
        // http://tools.ietf.org/html/rfc5192
        PANA_AGENT((byte) 136, (byte) 4, "PANA_AGENT"),
        // http://tools.ietf.org/html/rfc5223
        LOST_SERVER((byte) 137, (byte) 1, "LOST_SERVER"),
        // http://tools.ietf.org/html/rfc5417
        CAPWAP_AC_V4((byte) 138, (byte) 4, "CAPWAP_AC_V4"),
        // http://tools.ietf.org/html/rfc5678
        DHCP_MOS((byte) 139, (byte) 1, "DHCP_MOS"),
        DHCP_FQDN_MOS((byte) 140, (byte) 1, "DHCP_FQDN_MOS"),
        // http://tools.ietf.org/html/rfc6011
        SIP_UA_CONFIG_DOMAIN((byte) 141, (byte) 1, "SIP_UA_CONFIG_DOMAIN"),
        // http://tools.ietf.org/html/rfc6153
        ANDSF_SERVERS((byte) 142, (byte) 4, "ANDSF_SERVERS"),
        // http://tools.ietf.org/html/rfc6225
        DHCP_GEOLOC((byte) 144, (byte) 16, "DHCP_GEOLOC"),
        // http://tools.ietf.org/html/rfc6704
        FORCE_RENEW_NONCE_CAP((byte) 145, (byte) 1, "FORCE_RENEW_NONCE_CAP"),
        // http://tools.ietf.org/html/rfc6731
        RDNSS_SELECTION((byte) 146, (byte) 1, "RDNSS_SELECTION"),
        // http://tools.ietf.org/html/rfc5859
        TFTP_SERVER_ADDRESS((byte) 150, (byte) 4, "TFTP_SERVER_ADDRESS"),
        // http://tools.ietf.org/html/rfc6926
        STATUS_CODE((byte) 151, (byte) 1, "STATUS_CODE"),
        DHCP_BASE_TIME((byte) 152, (byte) 4, "DHCP_BASE_TIME"),
        DHCP_STATE_START_TIME((byte) 153, (byte) 4, "DHCP_STATE_START_TIME"),
        DHCP_QUERY_START_TIME((byte) 154, (byte) 4, "DHCP_QUERY_START_TIME"),
        DHCP_QUERY_END_TIME((byte) 155, (byte) 4, "DHCP_QUERY_END_TIME"),
        DHCP_STATE((byte) 156, (byte) 1, "DHCP_STATE"),
        DATA_SOURCE((byte) 157, (byte) 1, "DATA_SOURCE"),
        PCP_SERVER((byte) 158, (byte) 1, "PCP_SERVER"),
        // http://tools.ietf.org/html/rfc5071
        DHCP_PXE_MAGIC((byte) 208, (byte) 4, "DHCP_PXE_MAGIC"),
        CONFIG_FILE((byte) 209, (byte) 1, "CONFIG_FILE"),
        PATH_PREFIX((byte) 210, (byte) 4, "PATH_PREFIX"),
        REBOOT_TIME((byte) 211, (byte) 4, "REBOOT_TIME"),
        // http://tools.ietf.org/html/rfc5969
        DHCP_6RD((byte) 212, (byte) 1, "DHCP_6RD"),
        // http://tools.ietf.org/html/rfc5986
        DHCP_ACCESS_DOMAIN((byte) 213, (byte) 1, "DHCP_ACCESS_DOMAIN"),
        // http://tools.ietf.org/html/rfc6656
        SUBNET_ALLOCATION((byte) 220, (byte) 1, "SUBNET_ALLOCATION"),
        // http://tools.ietf.org/html/rfc6607
        VSS_OPTION((byte) 221, (byte) 1, "VSS_OPTION"),
        DHCP_VSS((byte) 221, (byte) 1, "DHCP_VSS"),

        END((byte) 255, (byte) 0, "END");

        private final byte code;
        private final byte length;
        private final String name;

        Code(byte code, byte length, String name) {
            this.code = code;
            this.length = length;
            this.name = name;
            CODE_TO_NAME.put(code, name);
            NAME_TO_CODE.put(name, code);
            CODE_TO_OPTION.put(code, this);
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

        public static final Code[] IPS_REQUIRED_DHCP_OPTION_CODES = {
            ROUTER,
            TIME_SERVER,
            NAME_SERVER,
            DNS,
            LOG_SERVER,
            QUOTES_SERVER,
            LPR_SERVER,
            IMPRESS_SERVER,
            RESOURCE_LOCATION_SERVER,
            NIS_SERVERS,
            NTP_SERVERS,
            NETBIOS_OVER_TCP_IP_NAME_SERVER,
            NETBIOS_OVER_TCP_IP_DATAGRAM_DISTRIBUTION_SERVER,
            X_WINDOW_SYSTEM_FONT_SRVER,
            X_WINDOW_SYSTEM_DISPLAY_MANGER,
            NETWORK_INFORMATION_SREVICE_AND_SERVERS,
            MOBILE_IP_HOME_AGENT,
            SMTP_SERVER,
            POP3_SERVER,
            NNTP_SERVER,
            DEFAULT_WWW_SERVER,
            DEFAULT_FINGER_SERVER,
            DEFAULT_IRC_SERVER,
            STREETTALK_SERVER,
            STDA_SERVER,
            NDS_SERVERS,
            ASSOCIATED_IP,
            LDAP_SERVERS,
            NETINFO_SERVER_TAG,
            TFTP_SERVER,
            PANA_AGENT,
            CAPWAP_AC_V4,
            ANDSF_SERVERS,
            TFTP_SERVER_ADDRESS
        };

        public static final Code[] SINGLE_IP_REQUIRED_DHCP_OPTION_CODES = {
            MASK,
            SWAP_SERVER,
            BCAST_ADDR,
            ROUTER_SOLITATION_ADDRESS,
            REQUESTED_IP,
            SERVER_ID,
            SUBNET_SELECTION
        };

        public static final Code[] NUMBER_REQUIRED_DHCP_OPTION_CODES = {
            TIME_OFFSET,
            BOOT_FILE_SIZE,
            MAXIMUM_DATAGRAM_REASSEMBLY_SIZE,
            PATH_MTU_AGING_TIMEOUT,
            PATH_MTU_PLATEAU_TABLE,
            INTERFACE_MTU,
            ARP_CACHE_TIMEOUT,
            TCP_KEEPALIVE_INTERVAL,
            IP_LEASE_TIME,
            MAXIMUM_DHCP_MESSAGE_SIZE,
            RENEWAL_TIME_VALUE,
            REBINDING_TIME_VALUE,
            DHCP_CLIENT_LAST_TIME,
            SYSTEM_ARCHITECTURE,
            NAME_SEARCH,
            DHCP_BASE_TIME,
            DHCP_STATE_START_TIME,
            DHCP_QUERY_START_TIME,
            DHCP_QUERY_END_TIME,
            DHCP_PXE_MAGIC,
            REBOOT_TIME
        };

        public static final Code[] BYTE_REQUIRED_DHCP_OPTION_CODES = {
            DEFAULT_IP_TTL,
            TCP_DEFAULT_TTL,
            DATA_SOURCE,
        };

        public static final Code[] BOOLEAN_REQUIRED_DHCP_OPTION_CODES = {
            IP_FOWARDING_ENABLE_DISABLE,
            NON_LOCAL_SOURCE_ROUTING_ENABLE_DISABLE,
            ALL_SUBNETS_ARE_LOCAL,
            PERFORM_MASK_DISCOVERY,
            MASK_SUPPLIER,
            PERFORM_MASK_DISCOVERY,
            TRAILER_ENCAPSULATION_OPTION,
            ETHERNET_ENCAPSULATION,
            TCP_KEEPALIVE_GARBAGE,
            AUTO_CONFIGURE,
        };

        public static final Code[] CIDR_REQUIRED_DHCP_OPTION_CODES = {
            POLICY_FILTER,
            STATIC_ROUTE
        };

        public static final Code[] BYTE_FOLLOWED_BY_A_STRING_OPTION_CODES = {
            CLIENT_IDENTIFIER,
            SLP_SERVICE_SCOPE,
            STATUS_CODE,
            DHCP_VSS
        };
    }

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
