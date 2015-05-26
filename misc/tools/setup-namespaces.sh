#!/bin/sh

# Copyright 2014 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# This scripts creates two network namespaces with a network interface
# each that's mapped to a network interface in the host, which can then
# be plugged to the datapath by binding it to midonet's virtual topology.

# The namespaces will be called 'left' and 'right'
# The interfaces prepared for binding to Midonet's vports are 'leftdp' and
# 'rightdp' respectively.
# The interfaces inside the namespaces are 'leftns' and 'rightns'

# 'leftns' and 'rightns' are given addresses 10.25.25.1/24 and 10.25.25.2/24
# respectively, so this script expects that they will be plugged to the same
# virtual bridge inside midonet.

# Once the script has been run, the virtual topology is setup as explained above
# and midolman is up and running, traffic can be sent from one namespace to the
# other, and arbitrary commands can be run in side them. Two examples:

# Ping right from left:
#   ip netns exec left ping 10.25.25.2
# tcpdump on the right side:
#   ip netns exec right tcpdump -i rightns

ip netns add left
ip link add name leftdp type veth peer name leftns
ip link set leftdp up
ip link set leftns netns left
ip netns exec left ip link set leftns up
ip netns exec left ip address add 10.25.25.1/24 dev leftns
ip netns exec left ip link set dev lo up


ip netns add right
ip link add name rightdp type veth peer name rightns
ip link set rightdp up
ip link set rightns netns right
ip netns exec right ip link set rightns up
ip netns exec right ip address add 10.25.25.2/24 dev rightns
ip netns exec right ip link set dev lo up
