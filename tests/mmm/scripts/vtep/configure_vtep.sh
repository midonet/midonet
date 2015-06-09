#!/bin/bash
# Vtep configuration

# Usage: /root/configure_vtep.sh
# The following env variables can be set to change the behavior
# VTEP_MGMT: (public) management ip for the vtep
# VTEP_TUNNEL: (public) tunnel ip for the vtep
# VTEP_PORTS: number of 'physical' vtep ports
# VTEP_HOST_ADDRESS_BASE: base address for emulated vtep-side
#     hosts; actual addresses are obtained by adding
#     1-VTEP_PORTS to the last byte of the base address

#
# Sanity check: openvswitch module must be loaded in the kernel
#
modprobe openvswitch
lsmod | grep -q -w openvswitch
if [ $? -ne 0 ]; then
    echo "openvswitch kernel module is not loaded!"
    exit 1
fi

# Local IP (as seen from inside the docker)
LOCAL_IP=$(ip -4 addr show eth0 scope global | \
grep '^ *inet ' | \
awk '{ print $2 }' | \
cut -f 1 -d /)

# Number of ports in the VTEP
if [ "x$VTEP_PORTS" = "x" ]; then
    VTEP_PORTS=4
fi

# Base address for emulated vtep-side hosts
if [ "x$VTEP_HOST_ADDRESS_BASE" = "x" ]; then
    VTEP_HOST_ADDRESS_BASE="10.0.10.0"
fi
NETBASE=$(echo "$VTEP_HOST_ADDRESS_BASE" | cut -f 1-3 -d .)
HOSTBASE=$(echo "$VTEP_HOST_ADDRESS_BASE" | cut -f 4 -d .)

# Management ip
if [ "x$VTEP_MGMT" = "x" ]; then
    VTEP_MGMT=$LOCAL_IP
fi

# Tunnel ip
if [ "x$VTEP_TUNNEL" = "x" ]; then
    VTEP_TUNNEL=$VTEP_MGMT
fi

if [ "$VTEP_MGMT" != "$VTEP_TUNNEL" ]; then
    if [ ! -d "/sys/devices/virtual/net/eth1" ]; then
        echo "eth1 interface required for different mgmt and tunnel ips"
        echo "waiting for eth1 to appear"
        COUNTDOWN=300
        while [ $COUNTDOWN -gt 0 ]; do
            [ -d "/sys/devices/virtual/net/eth1" ] && break
            sleep 1
            COUNTDOWN=$(($COUNTDOWN - 1))
        done
        if [ ! -d /sys/devices/virtual/net/eth1 ]; then
            echo "timed out"
            exit 1
        fi
        echo "eth1 ready"
    fi
fi

# Configure vtep physical switch
# (physical VTEP-side ports are named in1-in(n))
vtep-ctl add-ps vtep0
for i in $(seq 1 $VTEP_PORTS); do
    vtep-ctl add-port vtep0 "in${i}"
done
vtep-ctl set Physical_Switch vtep0 management_ips=$VTEP_MGMT
vtep-ctl set Physical_Switch vtep0 tunnel_ips=$VTEP_TUNNEL

# Configure corresponding openvswitch bridge
ovs-vsctl add-br vtep0
for i in $(seq 1 $VTEP_PORTS); do
    ovs-vsctl add-port vtep0 "in${i}"
done

# Create name spaces representing hosts behind the vtep ports
# (name spaces are named ns1 to ns(n); host-side ports are
# named out1-out(n), and they are linked # to the corresponding
# in1-in(n) ports in VTEP)
for i in $(seq 1 $VTEP_PORTS); do
    ip netns add "ns${i}"
    ip link add "in${i}" type veth peer name "out${i}"
    ip link set "out${i}" netns "ns${i}"
    ifconfig "in${i}" up
    ip netns exec "ns${i}" ifconfig lo up
    ip netns exec "ns${i}" ifconfig "out${i}" "${NETBASE}.$(($HOSTBASE + $i))/24" up
done

# Restart the vtep emulator to make sure that configuration is consolidated
/etc/init.d/openvswitch-vtep stop
/etc/init.d/openvswitch-vtep start

