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

/**
 * Interface listing constant short values for netlink message types.
 * See include/uapi/linux/netlink.h in Linux kernel sources.
 */
public interface NLMessageType {
    short NOOP    = (short) 0x0001;
    short ERROR   = (short) 0x0002;
    short DONE    = (short) 0x0003;
    short OVERRUN = (short) 0x0004;
    short NLMSG_MIN_TYPE = (short) 0x10;
}
