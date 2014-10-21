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

import java.nio.channels.spi.SelectorProvider;

import sun.nio.ch.SelChImpl;

/**
 * Specialization of a UnixDomainChannel that extends SelChImpl to
 * work as a SelectableChannel within sun implementation of java.nio.
 */
class UnixDomainChannelImpl extends UnixDomainChannel implements SelChImpl {

    public UnixDomainChannelImpl(SelectorProvider provider,
                                 AfUnix.Type sockType) {
        super(provider, sockType);
    }

    public UnixDomainChannelImpl(SelectorProvider provider,
                                 AfUnix.Address parentLocalAddress,
                                 AfUnix.Address remoteAddress,
                                 int childSocket) {
        super(provider, parentLocalAddress, remoteAddress, childSocket);
    }

}
