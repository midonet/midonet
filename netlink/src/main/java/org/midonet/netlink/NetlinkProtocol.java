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

/** Netlink protocols. */
public enum NetlinkProtocol {

    NETLINK_ROUTE(0),
    NETLINK_W1(1),
    NETLINK_USERSOCK(2),
    NETLINK_FIREWALL(3),
    NETLINK_INET_DIAG(4),
    NETLINK_NFLOG(5),
    NETLINK_XFRM(6),
    NETLINK_SELINUX(7),
    NETLINK_ISCSI(8),
    NETLINK_AUDIT(9),
    NETLINK_FIB_LOOKUP(10),
    NETLINK_CONNECTOR(11),
    NETLINK_NETFILTER(12),
    NETLINK_IP6_FW(13),
    NETLINK_DNRTMSG(14),
    NETLINK_KOBJECT_UEVENT(15),
    NETLINK_GENERIC(16),
    NETLINK_SCSITRANSPORT(18),
    NETLINK_ECRYPTFS(19),
    NETLINK_RDMA(20),
    NETLINK_CRYPTO(21);

    private final int value;

    private NetlinkProtocol(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }
}
