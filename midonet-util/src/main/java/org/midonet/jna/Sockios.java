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

/**
 * JNA implementation of type and function definitions from the linux/sockios.h
 * header.
 */
@SuppressWarnings("unused")
public interface Sockios {

    // Routing table calls.
    int SIOCADDRT = 0x890B;             // Add routing table entry.
    int SIOCDELRT = 0x890C;             // Delete routing table entry.
    int SIOCRTMSG = 0x890D;             // Unused.

    // Socket configuration controls.
    int SIOCGIFNAME = 0x8910;           // Get interface name.
    int SIOCSIFLINK = 0x8911;           // Set interface channel.
    int SIOCGIFCONF = 0x8912;           // Get interface list.
    int SIOCGIFFLAGS = 0x8913;          // Get flags.
    int SIOCSIFFLAGS = 0x8914;          // Set flags.
    int SIOCGIFADDR = 0x8915;           // Get PA address.
    int SIOCSIFADDR = 0x8916;           // Set PA address.
    int SIOCGIFDSTADDR = 0x8917;        // Get remote PA address.
    int SIOCSIFDSTADDR = 0x8918;        // Set remote PA address.
    int SIOCGIFBRDADDR = 0x8919;        // Get broadcast PA address.
    int SIOCSIFBRDADDR = 0x891a;        // Set broadcast PA address.
    int SIOCGIFNETMASK = 0x891b;        // Get network PA mask.
    int SIOCSIFNETMASK = 0x891c;        // Set network PA mask.
    int SIOCGIFMETRIC = 0x891d;         // Get metric.
    int SIOCSIFMETRIC = 0x891e;         // Set metric.
    int SIOCGIFMEM = 0x891f;            // Get memory address (BSD).
    int SIOCSIFMEM = 0x8920;            // Set memory address (BSD).
    int SIOCGIFMTU = 0x8921;            // Get MTU size.
    int SIOCSIFMTU = 0x8922;            // Set MTU size.
    int SIOCSIFNAME = 0x8923;           // Set interface name.
    int SIOCSIFHWADDR = 0x8924;         // Set hardware address.
    int SIOCGIFENCAP = 0x8925;          // Get/set encapsulations.
    int SIOCSIFENCAP = 0x8926;
    int SIOCGIFHWADDR = 0x8927;         // Get hardware address.
    int SIOCGIFSLAVE = 0x8929;          // Driver slaving support.
    int SIOCSIFSLAVE = 0x8930;
    int SIOCADDMULTI = 0x8931;          // Multicast address lists.
    int SIOCDELMULTI = 0x8932;
    int SIOCGIFINDEX = 0x8933;          // Name to interface index mapping.
    int SIOCSIFPFLAGS = 0x8934;         // Get/set extended flags set
    int SIOCGIFPFLAGS = 0x8935;
    int SIOCDIFADDR = 0x8936;           // Delete PA address.
    int SIOCSIFHWBROADCAST = 0x8937;    // Set hardware broadcast address.
    int SIOCGIFCOUNT = 0x8938;          // Get number of devices.

    int SIOCGIFBR = 0x8940;             // Bridging support.
    int SIOCSIFBR = 0x8941;             // Set bridging options.

    int SIOCGIFTXQLEN = 0x8942;         // Get the tx queue length.
    int SIOCSIFTXQLEN = 0x8943;         // Set the tx queue length.

    int SIOCETHTOOL = 0x8946;           // Ethtool interface.

    int SIOCGMIIPHY = 0x8947;           // Get address of MII PHY in use.
    int SIOCGMIIREG = 0x8948;           // Read MII PHY register.
    int SIOCSMIIREG = 0x8949;           // Write MII PHY register.

    int SIOCWANDEV = 0x894A;            // Get/set netdev parameters.

    int SIOCOUTQNSD = 0x894B;           // Output queue size (not sent only).

    // ARP cache control calls.
    int SIOCDARP = 0x8953;              // Delete ARP table entry.
    int SIOCGARP = 0x8954;              // Get ARP table entry.
    int SIOCSARP = 0x8955;              // Set ARP table entry.

    // RARP cache control calls.
    int SIOCDRARP = 0x8960;             // Delete RARP table entry.
    int SIOCGRARP = 0x8961;             // Get RARP table entry.
    int SIOCSRARP = 0x8962;             // Set RARP table entry.

    // Driver configuration calls.
    int SIOCGIFMAP = 0x8970;            // Get device parameters.
    int SIOCSIFMAP = 0x8971;            // Set device parameters.

    // DLCI configuration calls.
    int SIOCADDDLCI = 0x8980;            // Create new DLCI device.
    int SIOCDELDLCI = 0x8981;            // Delete DLCI device.

    int SIOCGIFVLAN = 0x8982;            // 802.1Q VLAN support.
    int SIOCSIFVLAN = 0x8983;            // Set 802.1Q VLAN options.

    // Bonding calls.
    int SIOCBONDENSLAVE = 0x8990;        // Enslave a device to the bond.
    int SIOCBONDRELEASE = 0x8991;        // Release a slave from the bond.
    int SIOCBONDSETHWADDR = 0x8992;      // Set the hw addr of the bond.
    int SIOCBONDSLAVEINFOQUERY = 0x8993; // Rtn info about slave state.
    int SIOCBONDINFOQUERY = 0x8994;      // Rtn info about bond state.
    int SIOCBONDCHANGEACTIVE = 0x8995;   // Update to a new active slave.

    // Bridge calls.
    int SIOCBRADDBR = 0x89a0;            // Create new bridge device.
    int SIOCBRDELBR = 0x89a1;            // Remove bridge device.
    int SIOCBRADDIF = 0x89a2;            // Add interface to bridge.
    int SIOCBRDELIF = 0x89a3;            // Remove interface from bridge.

    // Hardware time stamping: parameters in linux/net_tstamp.h.
    int SIOCSHWTSTAMP = 0x89b0;          // Set and get config.
    int SIOCGHWTSTAMP = 0x89b1;          // Get config.

}
