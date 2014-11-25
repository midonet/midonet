/*
 * Copyright 2015 Midokura SARL
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

public final class Rtnetlink {

    public interface Type {
        short NEWLINK = 16;
        short DELLINK = 17;
        short GETLINK = 18;
        short SETLINK = 19;

        short NEWADDR = 20;
        short DELADDR = 21;
        short GETADDR = 22;

        short NEWROUTE = 24;
        short DELROUTE = 25;
        short GETROUTE = 26;

        short NEWNEIGH = 28;
        short DELNEIGH = 29;
        short GETNEIGH = 30;

        short NEWRULE = 32;
        short DELRULE = 33;
        short GETRULE = 34;

        short NEWQDISC = 36;
        short DELQDISC = 37;
        short GETQDISC = 38;

        short NEWTCLASS = 40;
        short DELTCLASS = 41;
        short GETTCLASS = 42;

        short NEWTFILTER = 44;
        short DELTFILTER = 45;
        short GETTFILTER = 46;

        short NEWACTION = 48;
        short DELACTION = 49;
        short GETACTION = 50;

        short NEWPREFIX = 52;

        short GETMULTICAST = 58;

        short GETANYCAST = 62;

        short NEWNEIGHTBL = 64;

        short GETNEIGHTBL = 66;
        short SETNEIGHTBL = 67;

        short NEWNDUSEROPT = 68;

        short NEWADDRLABEL = 72;
        short DELADDRLABEL = 73;
        short GETADDRLABEL = 74;

        short GETDCB = 78;
    }

    public enum Group {
        LINK(1),
        NOTIFY(2),
        NEIGH(3),
        TC(4),
        IPV4_IFADDR(5),
        IPV4_MROUTE(6),
        IPV4_ROUTE(7),
        IPV4_RULE(8),
        IPV6_IFADDR(9),
        IPV6_MROUTE(10),
        IPV6_ROUTE(11),
        IPV6_IFINFO(12),
        DECnet_IFADDR(13),
        NOP2(14),
        DECnet_ROUTE(15),
        DECnet_RULE(16),
        NOP4(17),
        IPV6_PREFIX(18),
        IPV6_RULE(19),
        ND_USEROPT(20),
        PHONET_IFADDR(21),
        PHONET_ROUTE(22),
        DCB(23);

        public final int group;

        Group(int group) { this.group = group; }

        public int bitmask() { return 1 << (group -1); }
    }

}
