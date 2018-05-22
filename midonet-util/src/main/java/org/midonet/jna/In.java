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
import java.util.Collections;
import java.util.List;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/**
 * JNA implementation of type and function definitions from the linux/in.h
 * header.
 */
@SuppressWarnings("unused")
public interface In {

    /**
     * struct in_addr {
     *    __be32  s_addr;
     * };
     */
    class InAddr extends Structure {

        private static final List<String> FIELDS =
            Collections.singletonList("sAddr");

        public int sAddr;

        public InAddr() { }

        public InAddr(Pointer ptr) {
            super(ptr);
        }

        protected List<String> getFieldOrder() {
            return FIELDS;
        }

        public static class ByReference
            extends InAddr
            implements Structure.ByReference { }

        public static class ByValue
            extends InAddr
            implements Structure.ByValue { }
    }

    /**
     * #define __SOCK_SIZE__   16              // sizeof(struct sockaddr)
     * struct sockaddr_in {
     *   __kernel_sa_family_t  sin_family;     // Address family
     *   __be16                sin_port;       // Port number
     *   struct in_addr        sin_addr;       // Internet address
     *
     *   // Pad to size of `struct sockaddr'.
     *   unsigned char         __pad[__SOCK_SIZE__ - sizeof(short int) -
     *                         sizeof(unsigned short int) -
     *                         sizeof(struct in_addr)];
     * };
     */
    class SockAddrIn extends Structure {

        private static final List<String> FIELDS =
            Arrays.asList("sinFamily", "sinPort", "sinAddr", "pad");

        public short sinFamily;
        public short sinPort;
        public InAddr sinAddr;
        public byte[] pad = new byte[8];

        public SockAddrIn() { }

        public SockAddrIn(Pointer ptr) {
            super(ptr);
        }

        protected List<String> getFieldOrder() {
            return FIELDS;
        }

        public static class ByReference
            extends SockAddrIn
            implements Structure.ByReference { }

        public static class ByValue
            extends SockAddrIn
            implements Structure.ByValue { }
    }

}
