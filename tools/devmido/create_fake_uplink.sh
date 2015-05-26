#!/usr/bin/env bash

# Copyright 2015 Midokura SARL
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

# This script creates the fake uplink assuming that midonet is running
# correctly.  It takes the IP address CIDR as its argument which gets
# routed into the MidoNet provider router.  CIDR is defaulted to
# 200.200.200.0/24, but you can override by:
#
#      ./create_fake_uplink.sh 200.0.0.0/24
# or
#      CIDR=200.0.0.0/24 ./create_fake_uplink.sh
#

if [[ -n "$1" ]]; then
    CIDR=$1
else
    CIDR=${CIDR:-200.200.200.0/24}
fi

# Save the top directory and source the functions and midorc
TOP_DIR=$(cd $(dirname "$0") && pwd)
source $TOP_DIR/midorc
source $TOP_DIR/functions

HOST_NET='172.19.0.0/30'
HOST_IP_1='172.19.0.1'
HOST_IP_2='172.19.0.2'

set -e
set -x

# Grab the provider router
ROUTER_NAME='MidoNet Provider Router'
ROUTER_ID=$(midonet-cli -e router list | grep "$ROUTER_NAME" | \
    awk '{ print $2 }')
die_if_not_set $LINENO ROUTER_ID "FAILED to find the provider router"
echo "Provider Router: ${ROUTER_ID}"

# Get the host id of the devstack machine
HOST_ID=$(midonet-cli -e host list | awk '{ print $2 }')
die_if_not_set $LINENO HOST_ID "FAILED to obtain host id"
echo "Host: ${HOST_ID}"

# Check if the default tunnel zone exists
TZ_NAME='default_tz'
TZ_ID=$(midonet-cli -e list tunnel-zone name $TZ_NAME | awk '{ print $2 }')
if [[ -z "$TZ_ID" ]]; then
    TZ_ID=$(midonet-cli -e create tunnel-zone name default_tz type gre)
fi
echo "Tunnel Zone: ${TZ_ID}"

# Check if the host is a member of the tunnel zone
TZ_MEMBER=$(midonet-cli -e tunnel-zone $TZ_ID list member host $HOST_ID \
    address $HOST_IP_2)
if [[ -z "$TZ_MEMBER" ]]; then
    TZ_MEMBER=$(midonet-cli -e tunnel-zone $TZ_ID add member host $HOST_ID \
        address $HOST_IP_2)
fi
echo "Tunnel Zone Member: ${TZ_MEMBER}"

# Add a port in the MidoNet Provider Router that will be part of a /30 network
PORT_ID=$(midonet-cli -e router $ROUTER_ID list port address $HOST_IP_2 \
    net $HOST_NET | awk '{ print $2}')
if [[ -z "$PORT_ID" ]]; then
    PORT_ID=$(midonet-cli -e router $ROUTER_ID add port address $HOST_IP_2 \
        net $HOST_NET)
fi
echo "Router Port: ${PORT_ID}"

# Add a route on the provider router
ROUTE=$(midonet-cli -e router $ROUTER_ID list route port $PORT_ID \
    gw $HOST_IP_1 src 0.0.0.0/0 dst 0.0.0.0/0)
if [[ -z "$ROUTE" ]]; then
    ROUTE=$(midonet-cli -e router $ROUTER_ID add route src 0.0.0.0/0 \
        dst 0.0.0.0/0 type normal port $PORT_ID gw $HOST_IP_1)
fi
echo "Route: ${ROUTE}"

# Bind the virtual port to the veth interface
BINDING=$(midonet-cli -e host $HOST_ID list binding port $PORT_ID \
    interface veth1)
if [[ -z "$BINDING" ]]; then
    BINDING=$(midonet-cli -e host $HOST_ID add binding \
        port router $ROUTER_ID port $PORT_ID interface veth1)
fi
echo "Binding: ${BINDING}"

# Create the veth interfaces
sudo ip link add type veth
sudo ip link set dev veth0 up
sudo ip link set dev veth1 up

# create the linux bridge, give to it an IP address and attach the veth0
# interface
if ! ip link show dev uplinkbridge; then
    sudo brctl addbr uplinkbridge
fi

if ! brctl show uplinkbridge | grep veth0; then
    sudo brctl addif uplinkbridge veth0
fi

if ! ip addr | grep uplinkbridge | grep 172.19.0.1; then
    sudo ip addr add 172.19.0.1/30 dev uplinkbridge
fi

sudo ip link set dev uplinkbridge up

# allow ip forwarding
sudo sysctl -w net.ipv4.ip_forward=1

# route packets from physical underlay network to the bridge if the
# destination belongs to the cidr provided
if ! ip route | grep $CIDR; then
    sudo ip route add $CIDR via 172.19.0.2
fi

echo "Successfully created fake uplink"
