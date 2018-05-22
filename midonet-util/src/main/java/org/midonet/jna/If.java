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
import com.sun.jna.Union;

import org.midonet.Util;
import org.midonet.packets.IPv4Addr;

/**
 * JNA implementation of type and function definitions from the linux/if.h
 * header.
 */
@SuppressWarnings("unused")
public interface If {

    int IFNAMSIZ = 16;

    /**
     * struct ifreq {
     * #define IFHWADDRLEN     6
     *         union
     *         {
     *                 char    ifrn_name[IFNAMSIZ];     // if name, e.g. "en0"
     *         } ifr_ifrn;
     *
     *         union {
     *                 struct  sockaddr ifru_addr;
     *                 struct  sockaddr ifru_dstaddr;
     *                 struct  sockaddr ifru_broadaddr;
     *                 struct  sockaddr ifru_netmask;
     *                 struct  sockaddr ifru_hwaddr;
     *                 short   ifru_flags;
     *                 int     ifru_ivalue;
     *                 int     ifru_mtu;
     *                 struct  ifmap ifru_map;
     *                 char    ifru_slave[IFNAMSIZ];   // Just fits the size
     *                 char    ifru_newname[IFNAMSIZ];
     *                 void __user *   ifru_data;
     *                 struct  if_settings ifru_settings;
     *         } ifr_ifru;
     * };
     */
    class IfReq extends Structure {

        public static class IfrIfrn extends Union {

            public static final String IFRN_NAME = "ifrnName";

            public byte[] ifrnName = new byte[IFNAMSIZ];

            public IfrIfrn() { }

            public void setName(String name) {
                setType(IFRN_NAME);
                int i = 0;
                for (; i < name.length() && i < ifrnName.length; i++) {
                    ifrnName[i] = (byte) name.charAt(i);
                }
                for (; i < ifrnName.length; i++) {
                    ifrnName[i] = 0;
                }
            }
        }

        public static class IfrIfru extends Union {

            public static final String IFRU_ADDR = "ifruAddr";
            public static final String IFRU_FLAGS = "ifruFlags";
            public static final String IFRU_MTU = "ifruMtu";
            public static final String IFRU_INDEX = "ifruIndex";
            public static final String IFRU_NAME = "ifruName";
            public static final String IFRU_DATA = "ifruData";

            public Socket.SockAddr ifruAddr;
            public short ifruFlags;
            public int ifruMtu;
            public int ifruIndex;
            public byte[] ifruName = new byte[IFNAMSIZ];
            public Pointer ifruData;

            public IfrIfru() { setType(IFRU_ADDR); }

            public void setAddress(IPv4Addr address, short port) {
                setType(IFRU_ADDR);
                In.SockAddrIn sockAddrIn = new In.SockAddrIn(getPointer());
                sockAddrIn.sinFamily = Socket.AF_INET;
                sockAddrIn.sinAddr.sAddr = Util.hostToNetwork(address.addr());
                sockAddrIn.sinPort = Util.hostToNetwork(port);
                sockAddrIn.write();
                read();
            }

            public void setFlags(short flags) {
                setType(IFRU_FLAGS);
                ifruFlags = flags;
            }

            public void setMtu(int mtu) {
                setType(IFRU_MTU);
                ifruMtu = mtu;
            }

            public void setIndex(int index) {
                setType(IFRU_INDEX);
                ifruIndex = index;
            }

            public void setName(String name) {
                setType(IFRU_NAME);
                int i = 0;
                for (; i < name.length() && i < ifruName.length; i++) {
                    ifruName[i] = (byte) name.charAt(i);
                }
                for (; i < ifruName.length; i++) {
                    ifruName[i] = 0;
                }
            }

            public void setData(Pointer data) {
                setType(IFRU_DATA);
                ifruData = data;
            }
        }

        private static final List<String> FIELDS =
            Arrays.asList("ifrIfrn", "ifrIfru");

        public IfrIfrn ifrIfrn;
        public IfrIfru ifrIfru;

        public IfReq() { }

        public IfReq(Pointer ptr) {
            super(ptr);
        }

        protected List<String> getFieldOrder() {
            return FIELDS;
        }

        public static class ByReference
            extends IfReq
            implements Structure.ByReference { }
    }
}
