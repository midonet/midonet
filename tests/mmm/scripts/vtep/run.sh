#!/bin/sh

service ssh start && /etc/init.d/openvswitch-vtep start && /root/configure_vtep.sh && tail -f /var/log/openvswitch/ovs-vswitchd.log
