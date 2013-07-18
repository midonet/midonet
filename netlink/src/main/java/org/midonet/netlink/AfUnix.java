/*
* Copyright 2013 Midokura Europe SARL
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
