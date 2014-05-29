/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.netlink;

/**
 * Interface listing constant short values for netlink message types.
 * See include/uapi/linux/netlink.h in Linux kernel sources.
 */
public interface NLMessageType {
    short NOOP    = (short) 0x0001;
    short ERROR   = (short) 0x0002;
    short DONE    = (short) 0x0003;
    short OVERRUN = (short) 0x0004;
}
