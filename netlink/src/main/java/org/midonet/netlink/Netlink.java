/*
 * Copyright 2014 Midokura SARL
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
