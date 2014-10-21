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

import org.midonet.netlink.clib.cLibrary;

import javax.annotation.Nonnull;

public interface AfUnix {

    public enum Type {
        SOCK_DGRAM(cLibrary.SOCK_DGRAM), SOCK_STREAM(cLibrary.SOCK_STREAM);

        int value;

        private Type(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    public class Address extends java.net.SocketAddress {

        private String path;

        public Address(String path) {
            this.path = path;
        }

        public Address(cLibrary.UnixDomainSockAddress addr) {
            this.path = new String(addr.sun_path.chars);
        }

        public cLibrary.UnixDomainSockAddress toCLibrary() {
            cLibrary.UnixDomainSockAddress addr =
                new cLibrary.UnixDomainSockAddress();

            addr.sun_family = cLibrary.AF_UNIX;
            byte[] bytes = this.path.getBytes();
            for (int i = 0; i < bytes.length; i++)
                addr.sun_path.chars[i] = bytes[i];
            return addr;
        }

        public String getPath() {
            return path;
        }
    }
}
