#!/bin/bash
# Configure an additional bridge in the host for a second network interface
# The id of the container must be passed as a parameter
# Relevant environment variables:
# - DUAL_DOCKER_BRIDGE: the name of the secondary docker bridge
# - DUAL_IP: ip for the secondary docker bridge interface (if it does
#     not exist yet). 
#     It defaults to the ip of the docker0 interface, increasing the
#     2nd byte by one.
# - PEER_IP: IP for the second interface in the docker side (defaults to
#     DUAL_IP with the last byte increased by one.

#
# Sanity check
#
sudo brctl show > /dev/null 2>&1
if [ $? -ne 0 ]; then
    echo "cannot use brctl command"
    exit 1
fi

#
# Determine the container
#

if [ "x$1" = "x" ]; then
    CONTAINERID=$(sudo docker ps | awk '{if ($2 ~ "^midokura/vtep-emu") print $1}' | head -n 1)
    echo misssing container id
    [ -n "$CONTAINERID" ] && echo "(maybe you mean $CONTAINERID ?)"
    exit 1
else
    CONTAINERID="$1"
fi

PID=$(sudo docker inspect -f '{{.State.Pid}}' $CONTAINERID)
sudo mkdir -p /var/run/netns
sudo ln -s /proc/$PID/ns/net /var/run/netns/$PID

#
# Determine relevant IPs
#

DOCKER_IP=$(ip -4 addr show docker0 scope global | \
grep '^ *inet ' | \
awk '{ print $2 }' | \
cut -f 1 -d /)

#
# Create the bridge
#

if [ "x$DUAL_DOCKER_BRIDGE" = "x" ]; then
    DUAL_DOCKER_BRIDGE=dockerdual0
fi

sudo brctl show | awk '{print $1}' | grep -q -w "$DUAL_DOCKER_BRIDGE"
if [ $? -eq 0 ]; then
    echo "secondary bridge alredy exists: $DUAL_DOCKER_BRIDGE"
    DUAL_IP=$(ip -4 addr show "$DUAL_DOCKER_BRIDGE" scope global | \
        grep '^ *inet ' | \
        awk '{ print $2 }' | \
        cut -f 1 -d /)

else
    if [ "x$DUAL_IP" = "x" ]; then
        BYTES=( $(echo $DOCKER_IP | sed -e 's/\./ /g') )
        DUAL_IP=${BYTES[0]}"."$((${BYTES[1]} + 1))"."${BYTES[2]}"."${BYTES[3]}"/16"
    fi
    sudo brctl addbr $DUAL_DOCKER_BRIDGE
    sudo ip addr add $DUAL_IP dev $DUAL_DOCKER_BRIDGE
    sudo ip link set dev $DUAL_DOCKER_BRIDGE up
fi

#
# Create interfaces
#
BYTES=( $(echo $DUAL_IP | sed -e 's/\/.*//' | sed -e 's/\./ /g') )
PEER_NET=${BYTES[0]}"."${BYTES[1]}".0.0/16"
if [ "x$PEER_IP" = "x" ]; then
    PEER_IP=${BYTES[0]}"."${BYTES[1]}"."${BYTES[2]}"."$((${BYTES[3]} + 1))"/16"
fi
HOSTIF=veth"$(echo $CONTAINERID | cut -c -6)"
DOCKIF=peer"$(echo $CONTAINERID | cut -c -6)"

sudo ip link add $HOSTIF type veth peer name $DOCKIF
sudo brctl addif $DUAL_DOCKER_BRIDGE $HOSTIF
sudo ip link set $HOSTIF up

#
# Move peer into docker container as eth1
#
sudo ip link set $DOCKIF netns $PID
sudo ip netns exec $PID ip link set dev $DOCKIF name eth1
sudo ip netns exec $PID ip link set eth1 address 12:34:56:78:9a:bc
sudo ip netns exec $PID ip link set eth1 up
sudo ip netns exec $PID ip addr add $PEER_IP dev eth1
sudo ip netns exec $PID ip route add $PEER_NET dev eth1

