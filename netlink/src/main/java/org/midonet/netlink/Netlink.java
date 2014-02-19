/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.netlink;

import java.nio.channels.IllegalSelectorException;
import java.nio.channels.spi.SelectorProvider;
import javax.annotation.Nonnull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netlink API interface.
 */
public abstract class Netlink {

    private static final Logger log = LoggerFactory.getLogger(Netlink.class);

    /** returns the system-wide selector provider used to instantiate
     *  selectable Netlink and UnixDomainSocket channels, in addition to
     *  any other java.nio channels. */
    public static NetlinkSelectorProvider selectorProvider() {

        SelectorProvider provider = SelectorProvider.provider();

        if (!(provider instanceof NetlinkSelectorProvider)) {
            log.error("Invalid selector type: {} => jdk-bootstrap shadowing "
                + "may have failed ?", provider.getClass());
            throw new IllegalSelectorException();
        }

        return (NetlinkSelectorProvider) provider;
    }

    public enum Flag {
        // Must be set on all request messages.
        NLM_F_REQUEST(0x001),
        // The message is part of a multipart msg
        // terminated by NLMSG_DONE.
        NLM_F_MULTI(0x002),
        // Request for an acknowledgment on success.
        NLM_F_ACK(0x004),
        // Echo this request.
        NLM_F_ECHO(0x008),

        // for READ
        // Return the complete table instead of a single entry.
        NLM_F_ROOT(0x100),
        // Return all entries matching criteria passed
        // in message content. Not implemented yet.
        NLM_F_MATCH(0x200),
        // Return an atomic snapshot of the table.
        NLM_F_ATOMIC(0x400),
        //  Convenience macro;
        NLM_F_DUMP(0x200 | 0x400),

        // for NEW
        // Replace existing matching object.
        NLM_F_REPLACE(0x100),
        // Don't replace if already exists.
        NLM_F_EXCL(0x200),
        // Create object if it doesn't already exist.
        NLM_F_CREATE(0x400),
        // Add to the end of the object list.
        NLM_F_APPEND(0x800),;

        private short value;

        Flag(int value) {
            this.value = (short) value;
        }

        public short getValue() {
            return value;
        }

        public static short or(Netlink.Flag... flags) {
            int value = 0;
            for (Netlink.Flag flag : flags) {
                value |= flag.getValue();
            }

            return (short) value;
        }

        public static boolean isSet(short flags, Flag flag) {
            return (flags & flag.getValue()) != 0;
        }
    }

    public enum MessageType {
        NLMSG_NOOP(0x0001),
        NLMSG_ERROR(0x0002),
        NLMSG_DONE(0x0003),
        NLMSG_OVERRUN(0x0004),

        NLMSG_CUSTOM(Short.MAX_VALUE);


        short value;

        MessageType(int value) {
            this.value = (short) value;
        }

        @Nonnull
        public static MessageType findById(short type) {
            switch (type) {
                case 1:
                    return NLMSG_NOOP;
                case 2:
                    return NLMSG_ERROR;
                case 3:
                    return NLMSG_DONE;
                case 4:
                    return NLMSG_OVERRUN;
                default:
                    return NLMSG_CUSTOM;
            }
        }
    }


    /**
     * Abstracts a netlink address.
     */
    public static class Address {

        private final int pid;

        public Address(int pid) {
            this.pid = pid;
        }

        public int getPid() {
            return pid;
        }
    }

}
