#!/bin/bash


# Midonet do not support ipv6
sysctl -w net.ipv6.conf.all.disable_ipv6=1
sysctl -w net.ipv6.conf.default.disable_ipv6=1
sysctl -w net.ipv6.conf.lo.disable_ipv6=1

# Wait for configured interfaces to be set up
# The name of each additional interface should be provided
# in an env var with the _IFACE suffix.
for IFACE in `env | grep _IFACE | cut -d= -f2`; do
    # TODO: change pipework by native docker networking once stable
    echo "Waiting for interface $IFACE to be up"
    timeout 300s pipework --wait -i $IFACE
    if [ $? -eq 124 ]; then
        echo "Interface $IFACE was not ready after 120s. Exiting..."
        exit 1
    fi
done

exec /sbin/init
