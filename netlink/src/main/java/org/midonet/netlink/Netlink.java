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
