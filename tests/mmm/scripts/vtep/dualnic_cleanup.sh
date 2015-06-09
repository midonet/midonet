#!/bin/bash
# Cleanup the additional bridge created for a second network interface
# Relevant environment variables:
# - DUAL_DOCKER_BRIDGE: the name of the secondary docker bridge

#
# Sanity check
#
sudo brctl show > /dev/null 2>&1
if [ $? -ne 0 ]; then
    echo "cannot use brctl command"
    exit 1
fi

#
# Cleanup container net spaces
#
sudo find -L /var/run/netns -type l -delete 

#
# Create the bridge
#

if [ "x$DUAL_DOCKER_BRIDGE" = "x" ]; then
    DUAL_DOCKER_BRIDGE=dockerdual0
fi

sudo brctl show | awk '{print $1}' | grep -q -w "$DUAL_DOCKER_BRIDGE"
if [ $? -eq 0 ]; then
    sudo ip link set dev $DUAL_DOCKER_BRIDGE down
    sudo brctl delbr $DUAL_DOCKER_BRIDGE
else
    echo "bridge does not exists: $DUAL_DOCKER_BRIDGE"
    if [ "x$DUAL_IP" = "x" ]; then
        BYTES=( $(echo $DOCKER_IP | sed -e 's/\./ /g') )
        DUAL_IP=${BYTES[0]}"."${BYTES[1]}"."$((${BYTES[2]} + 1))"."${BYTES[3]}"/24"
    fi
fi

