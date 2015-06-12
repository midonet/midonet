#!/bin/bash

echo "Provisioning bgp interfaces..."

# Cleaning previous bridge if exists
sudo ip link set dev brbgp1 down
sudo brctl delbr brbgp1

sudo pipework/pipework brbgp1 -i bgp0 mnsandboxmdts_quagga_1 10.1.0.240/24
sudo pipework/pipework brbgp1 -i bgp1 mnsandboxmdts_quagga_1 10.2.0.240/24
sudo pipework/pipework brbgp1 -i bgp0 mnsandboxmdts_midolman1_1 10.1.0.1/24
sudo pipework/pipework brbgp1 -i bgp0 mnsandboxmdts_midolman2_1 10.2.0.1/24
