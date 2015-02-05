#!/bin/bash

# Remove the router
sudo ip link set dev uplinkbridge down
sudo brctl delbr uplinkbridge
sudo ip link set veth0 down
sudo ip link set veth1 down
sudo ip link del veth0
sudo ip link del veth1
