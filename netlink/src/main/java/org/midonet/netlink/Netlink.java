/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
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
