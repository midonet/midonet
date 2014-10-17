#!/usr/bin/python

# Copyright 2014 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import subprocess
import os
import re
import sys

interfaces_by_index = {}

def get_peer_index(ns, ifname):
    if ifname == "lo":
        return None

    match = re.search('@', ifname)
    if match:
        return None

    if ifname == "midonet":
        return None

    cmd = ""
    if ns == "root":
        cmd = "sudo ethtool -S " + ifname
    else:
        cmd = "sudo ip netns exec " + ns + " ethtool -S " + ifname

    output = ""
    try:
        devnull = open(os.devnull, 'w')
        output = subprocess.check_output(cmd.split(), stderr=devnull)
    except Exception as e:
        return None

    if output:
        return output.split('\n')[1].split(':')[1].lstrip()


def parse_interfaces(output, ns):
    for line in output.splitlines():
        match = re.search("mtu", line)
        if match:
            i = line.split(':')
            ifindex = i[0]
            ifname = i[1].lstrip()
            peer_index = get_peer_index(ns, ifname)
            interfaces_by_index[ifindex] = (ns, ifname, peer_index)


def check_ns(ns):
    cmd = "sudo ip netns exec " + ns + " ip link"
    output = subprocess.check_output(cmd.split())
    parse_interfaces(output, ns)

def check_rootns():
    cmd = "ip link"
    output = subprocess.check_output(cmd.split())
    parse_interfaces(output, "root")

def check_ethtool():
    cmd = "which ethtool"
    try:
        output = subprocess.check_output(cmd.split())
    except Exception as e:
        print "ethtool is not installed"
        sys.exit(1)


def main():
    check_ethtool() 

    check_rootns()

    cmd = "ip netns"
    output = subprocess.check_output(cmd.split())
    for ns in output.split('\n'):
        if ns:
            check_ns(ns)

    for ifindex in interfaces_by_index:
        iface = interfaces_by_index[ifindex]
        ns = iface[0]
        ifname = iface[1]
        peer_index = iface[2]
        if peer_index:
            peer = interfaces_by_index[peer_index]
            peer_ns = peer[0]
            peer_ifname = peer[1]
            print ns, ifname, " --> ", peer_ns, peer_ifname
        else:
            print ns, ifname


if __name__ == "__main__":
    main()
