/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.jna;

import java.util.Arrays;
import java.util.List;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/**
 * JNA implementation of type and function definitions from the linux/ethtool.h
 * header.
 */
@SuppressWarnings("unused")
public interface Ethtool {

    int ETHTOOL_GSET = 0x00000001;     // Get settings.
    int ETHTOOL_SSET = 0x00000002;     // Set settings.
    int ETHTOOL_GDRVINFO = 0x00000003; // Get driver info.
    int ETHTOOL_GREGS = 0x00000004;    // Get NIC registers.
    int ETHTOOL_GWOL = 0x00000005;     // Get wake-on-lan options.
    int ETHTOOL_SWOL = 0x00000006;     // Set wake-on-lan options.
    int ETHTOOL_GMSGLVL = 0x00000007;  // Get driver message level.
    int ETHTOOL_SMSGLVL = 0x00000008;  // Set driver msg level.
    int ETHTOOL_NWAY_RST = 0x00000009; // Restart autonegotiation.

    /** Get link status for host, i.e. whether the interface *and* the
     * physical port (if there is one) are up (ethtool_value). */
    int ETHTOOL_GLINK = 0x0000000a;
    int ETHTOOL_GEEPROM = 0x0000000b;     // Get EEPROM data.
    int ETHTOOL_SEEPROM = 0x0000000c;     // Set EEPROM data.
    int ETHTOOL_GCOALESCE = 0x0000000e;   // Get coalesce config.
    int ETHTOOL_SCOALESCE = 0x0000000f;   // Set coalesce config.
    int ETHTOOL_GRINGPARAM = 0x00000010;  // Get ring parameters.
    int ETHTOOL_SRINGPARAM = 0x00000011;  // Set ring parameters.
    int ETHTOOL_GPAUSEPARAM = 0x00000012; // Get pause parameters.
    int ETHTOOL_SPAUSEPARAM = 0x00000013; // Set pause parameters.
    int ETHTOOL_GRXCSUM = 0x00000014;     // Get RX hw csum enable (ethtool_value).
    int ETHTOOL_SRXCSUM = 0x00000015;     // Set RX hw csum enable (ethtool_value).
    int ETHTOOL_GTXCSUM = 0x00000016;     // Get TX hw csum enable (ethtool_value).
    int ETHTOOL_STXCSUM = 0x00000017;     // Set TX hw csum enable (ethtool_value).
    int ETHTOOL_GSG = 0x00000018;         // Get scatter-gather enable (ethtool_value).
    int ETHTOOL_SSG = 0x00000019;         // Set scatter-gather enable (ethtool_value).
    int ETHTOOL_TEST = 0x0000001a;        // Execute NIC self-test.
    int ETHTOOL_GSTRINGS = 0x0000001b;    // Get specified string set.
    int ETHTOOL_PHYS_ID = 0x0000001c;     // Identify the NIC.
    int ETHTOOL_GSTATS = 0x0000001d;      // Get NIC-specific statistics.
    int ETHTOOL_GTSO = 0x0000001e;        // Get TSO enable (ethtool_value).
    int ETHTOOL_STSO = 0x0000001f;        // Set TSO enable (ethtool_value).
    int ETHTOOL_GPERMADDR = 0x00000020;   // Get permanent hardware address.
    int ETHTOOL_GUFO = 0x00000021;        // Get UFO enable (ethtool_value).
    int ETHTOOL_SUFO = 0x00000022;        // Set UFO enable (ethtool_value).
    int ETHTOOL_GGSO = 0x00000023;        // Get GSO enable (ethtool_value).
    int ETHTOOL_SGSO = 0x00000024;        // Set GSO enable (ethtool_value).
    int ETHTOOL_GFLAGS = 0x00000025;      // Get flags bitmap(ethtool_value).
    int ETHTOOL_SFLAGS = 0x00000026;      // Set flags bitmap(ethtool_value).
    int ETHTOOL_GPFLAGS = 0x00000027;     // Get driver-private flags bitmap.
    int ETHTOOL_SPFLAGS = 0x00000028;     // Set driver-private flags bitmap.

    int ETHTOOL_GRXFH = 0x00000029;       // Get RX flow hash configuration.
    int ETHTOOL_SRXFH = 0x0000002a;       // Set RX flow hash configuration.
    int ETHTOOL_GGRO = 0x0000002b;        // Get GRO enable (ethtool_value).
    int ETHTOOL_SGRO = 0x0000002c;        // Set GRO enable (ethtool_value).
    int ETHTOOL_GRXRINGS = 0x0000002d;    // Get RX rings available for LB.
    int ETHTOOL_GRXCLSRLCNT = 0x0000002e; // Get RX class rule count.
    int ETHTOOL_GRXCLSRULE = 0x0000002f;  // Get RX classification rule.
    int ETHTOOL_GRXCLSRLALL = 0x00000030; // Get all RX classification rule.
    int ETHTOOL_SRXCLSRLDEL = 0x00000031; // Delete RX classification rule.
    int ETHTOOL_SRXCLSRLINS = 0x00000032; // Insert RX classification rule.
    int ETHTOOL_FLASHDEV = 0x00000033;    // Flash firmware to device.
    int ETHTOOL_RESET = 0x00000034;       // Reset hardware.
    int ETHTOOL_SRXNTUPLE = 0x00000035;   // Add an n-tuple filter to device.
    int ETHTOOL_GRXNTUPLE = 0x00000036;   // Deprecated.
    int ETHTOOL_GSSET_INFO = 0x00000037;  // Get string set info.
    int ETHTOOL_GRXFHINDIR = 0x00000038;  // Get RX flow hash indir'n table.
    int ETHTOOL_SRXFHINDIR = 0x00000039;  // Set RX flow hash indir'n table.

    int ETHTOOL_GFEATURES = 0x0000003a;     // Get device offload settings.
    int ETHTOOL_SFEATURES = 0x0000003b;     // Change device offload settings.
    int ETHTOOL_GCHANNELS = 0x0000003c;     // Get no of channels.
    int ETHTOOL_SCHANNELS = 0x0000003d;     // Set no of channels.
    int ETHTOOL_SET_DUMP = 0x0000003e;      // Set dump settings.
    int ETHTOOL_GET_DUMP_FLAG = 0x0000003f; // Get dump settings.
    int ETHTOOL_GET_DUMP_DATA = 0x00000040; // Get dump data.
    int ETHTOOL_GET_TS_INFO = 0x00000041;   // Get time stamping and PHC info.
    int ETHTOOL_GMODULEINFO = 0x00000042;   // Get plug-in module information.
    int ETHTOOL_GMODULEEEPROM = 0x00000043; // Get plug-in module eeprom.
    int ETHTOOL_GEEE = 0x00000044;          // Get EEE settings.
    int ETHTOOL_SEEE = 0x00000045;          // Set EEE settings.

    int ETHTOOL_GRSSH = 0x00000046;         // Get RX flow hash configuration.
    int ETHTOOL_SRSSH = 0x00000047;         // Set RX flow hash configuration.
    int ETHTOOL_GTUNABLE = 0x00000048;      // Get tunable configuration.
    int ETHTOOL_STUNABLE = 0x00000049;      // Set tunable configuration.
    int ETHTOOL_GPHYSTATS = 0x0000004a;     // Get PHY-specific statistics.

    int ETHTOOL_PERQUEUE = 0x0000004b;      // Set per queue options.

    int ETHTOOL_GLINKSETTINGS = 0x0000004c; // Get ethtool_link_settings
    int ETHTOOL_SLINKSETTINGS = 0x0000004d; // Set ethtool_link_settings

    /**
     * struct ethtool_cmd {
     *   __u32   cmd;
     *   __u32   supported;
     *   __u32   advertising;
     *   __u16   speed;
     *   __u8    duplex;
     *   __u8    port;
     *   __u8    phy_address;
     *   __u8    transceiver;
     *   __u8    autoneg;
     *   __u8    mdio_support;
     *   __u32   maxtxpkt;
     *   __u32   maxrxpkt;
     *   __u16   speed_hi;
     *   __u8    eth_tp_mdix;
     *   __u8    eth_tp_mdix_ctrl;
     *   __u32   lp_advertising;
     *   __u32   reserved[2];
     * };
     */
    class EthtoolCmd extends Structure {

        private static final List<String> FIELDS =
            Arrays.asList("cmd", "supported", "advertising", "speed", "duplex",
                          "port", "phyAddress", "transceiver", "autoneg",
                          "mdioSupport", "maxtxpkt", "maxrxpkt", "speedHi",
                          "ethTpMdix", "ethTpMdixCtrl", "lpAdvertising",
                          "reserved");

        public int cmd;
        public int supported;
        public int advertising;
        public short speed;
        public byte duplex;
        public byte port;
        public byte phyAddress;
        public byte transceiver;
        public byte autoneg;
        public byte mdioSupport;
        public int maxtxpkt;
        public int maxrxpkt;
        public short speedHi;
        public byte ethTpMdix;
        public byte ethTpMdixCtrl;
        public int lpAdvertising;
        public int[] reserved = new int[2];

        public EthtoolCmd() { }

        public EthtoolCmd(Pointer ptr) {
            super(ptr);
        }

        protected List<String> getFieldOrder() {
            return FIELDS;
        }

        public static class ByReference
            extends EthtoolCmd
            implements Structure.ByReference { }

        public static class ByValue
            extends EthtoolCmd
            implements Structure.ByValue { }
    }

    /**
     * struct ethtool_value {
     *   __u32   cmd;
     *   __u32   data;
     * };
     */
    class EthtoolValue extends Structure {

        private static final List<String> FIELDS = Arrays.asList("cmd", "data");

        public int cmd;
        public int data;

        public EthtoolValue() { }

        public EthtoolValue(Pointer ptr) {
            super(ptr);
        }

        protected List<String> getFieldOrder() {
            return FIELDS;
        }

        public static class ByReference
            extends EthtoolValue
            implements Structure.ByReference { }

        public static class ByValue
            extends EthtoolValue
            implements Structure.ByValue { }
    }

}
