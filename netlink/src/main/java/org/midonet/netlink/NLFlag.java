/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.netlink;

/**
 * Interface listing constant short values for netlink message Flags.
 * See include/uapi/linux/netlink.h in Linux kernel sources.
 */
public class NLFlag {
    private NLFlag() {}

    public static final short REQUEST   = (short) 1;  /* It is request message.   */
    public static final short MULTI     = (short) 2;  /* Multipart message, terminated by NLMSG_DONE */
    public static final short ACK       = (short) 4;  /* Reply with ack, with zero or error code */
    public static final short ECHO      = (short) 8;  /* Echo this request    */
    public static final short DUMP_INTR = (short) 16; /* Dump was inconsistent due to sequence change */

    /* Modifiers to GET request */
    public interface Get {
        short ROOT    = (short) 0x100;  /* specify tree root  */
        short MATCH   = (short) 0x200;  /* return all matching  */
        short ATOMIC  = (short) 0x400;  /* atomic GET   */
        short DUMP    = ROOT | MATCH;
    }

    /* Modifiers to NEW request */
    public interface New {
        short REPLACE = (short) 0x100;  /* Override existing    */
        short EXCL    = (short) 0x200;  /* Do not touch, if it exists */
        short CREATE  = (short) 0x400;  /* Create, if it does not exist */
        short APPEND  = (short) 0x800;  /* Add to end of list   */
    }

    public static boolean isMultiFlagSet(short flags) {
        return (flags & MULTI) != 0;
    }
}
