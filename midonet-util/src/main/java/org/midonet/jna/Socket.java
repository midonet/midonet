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
 * JNA implementation of type and function definitions from the linux/socket.h
 * header.
 */
@SuppressWarnings("unused")
public interface Socket {

    int AF_UNIX = 1;        // Unix domain sockets.
    int AF_INET = 2;        // Internet IP Protocol.
    int AF_INET6 = 10;      // IP version 6.
    int AF_NETLINK = 16;    // Netlink.

    /**
     * struct sockaddr {
     *    sa_family_t     sa_family;      // address family, AF_xxx
     *    char            sa_data[14];    // 14 bytes of protocol address
     * };
     */
    class SockAddr extends Structure {

        private static final List<String> FIELDS =
            Arrays.asList("saFamily", "saData");

        public short saFamily;
        public byte[] saData = new byte[14];

        public SockAddr() { }

        public SockAddr(Pointer ptr) {
            super(ptr);
        }

        protected List<String> getFieldOrder() {
            return FIELDS;
        }

        public static class ByReference
            extends SockAddr
            implements Structure.ByReference { }

        public static class ByValue
            extends SockAddr
            implements Structure.ByValue { }
    }

}
