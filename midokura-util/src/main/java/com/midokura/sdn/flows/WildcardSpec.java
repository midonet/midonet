/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.sdn.flows;

public class WildcardSpec {
    final public static int IN_PORT = 1 << 0; /* Switch input port. */
    final public static int DL_VLAN = 1 << 1; /* VLAN id. */
    final public static int DL_VLAN_PCP = 1 << 20; /* VLAN priority. */
    final public static int DL_SRC = 1 << 2; /* Ethernet source address. */
    final public static int DL_DST = 1 << 3; /* Ethernet destination address. */
    final public static int DL_TYPE = 1 << 4; /* Ethernet frame type. */
    final public static int ARP_SRC = 1;
    final public static int ARP_DST = 1;
    final public static int IP4_PROTO = 1 << 5; /* IP protocol. */
    final public static int IP4_SRC = 1 << 6;
    final public static int IP4_DST = 1 << 7;
    final public static int IP4_TOS = 1 << 8; /* IP ToS (DSCP field, 6 bits). */
    final public static int TCP_SRC = 1 << 9; /* TCP source port. */
    final public static int TCP_DST = 1 << 10; /* TCP destination port. */
    final public static int UDP_SRC = 1 << 11; /* UDP source port. */
    final public static int UDP_DST = 1 << 12; /* UDP destination port. */

    protected long wildcards;
}
