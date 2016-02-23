#!/bin/bash

echo "Provisioning bgp interfaces..."

# Cleaning previous bridge if exists
sudo ip link set dev brbgp0 down
sudo ip link set dev brbgp1 down
sudo ip link set dev brbgp2 down
sudo brctl delbr brbgp0
sudo brctl delbr brbgp1
sudo brctl delbr brbgp2

# One quagga (master) advertising "inet" networks connected to two
# quaggas (peers) which will do the peering with midolman
sudo pipework/pipework brbgp0 -i bgp0 mnsandboxmdts_quagga0_1 10.10.0.1/24
sudo pipework/pipework brbgp0 -i bgp0 mnsandboxmdts_quagga1_1 10.10.0.2/24
sudo pipework/pipework brbgp0 -i bgp0 mnsandboxmdts_quagga2_1 10.10.0.3/24

# Two interfaces per quagga peer (for single and multiple sessions)
# BGP peer1
sudo pipework/pipework brbgp1 -i bgp1 mnsandboxmdts_quagga1_1 10.1.0.240/24
sudo pipework/pipework brbgp1 -i bgp2 mnsandboxmdts_quagga2_1 10.1.0.241/24

# BGP peer2
sudo pipework/pipework brbgp2 -i bgp1 mnsandboxmdts_quagga2_1 10.2.0.240/24
sudo pipework/pipework brbgp2 -i bgp2 mnsandboxmdts_quagga1_1 10.2.0.241/24

# An interface per midolman peer (for single and multiple sessions)
# MM peer1
sudo pipework/pipework brbgp1 -i bgp0 mnsandboxmdts_midolman1_1 10.1.0.1/24

# MM peer2
sudo pipework/pipework brbgp2 -i bgp0 mnsandboxmdts_midolman2_1 10.2.0.1/24
