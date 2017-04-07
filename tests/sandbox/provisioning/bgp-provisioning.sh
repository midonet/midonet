#!/bin/bash

echo "Provisioning bgp interfaces..."

# Cleaning previous bridge if exists
sudo ip link set dev brbgp0_${SANDBOX_NAME} down
sudo ip link set dev brbgp1_${SANDBOX_NAME} down
sudo ip link set dev brbgp2_${SANDBOX_NAME} down
sudo ip link del brbgp0_${SANDBOX_NAME}
sudo ip link del brbgp1_${SANDBOX_NAME}
sudo ip link del brbgp2_${SANDBOX_NAME}

# One quagga (master) advertising "inet" networks connected to two
# quaggas (peers) which will do the peering with midolman
sudo pipework/pipework brbgp0_${SANDBOX_NAME} -i bgp0 mnsandbox${SANDBOX_NAME}_quagga0_1 10.10.0.1/24
sudo pipework/pipework brbgp0_${SANDBOX_NAME} -i bgp0 mnsandbox${SANDBOX_NAME}_quagga1_1 10.10.0.2/24
sudo pipework/pipework brbgp0_${SANDBOX_NAME} -i bgp0 mnsandbox${SANDBOX_NAME}_quagga2_1 10.10.0.3/24

# Two interfaces per quagga peer (for single and multiple sessions)
# BGP peer1
sudo pipework/pipework brbgp1_${SANDBOX_NAME} -i bgp1 mnsandbox${SANDBOX_NAME}_quagga1_1 10.1.0.240/24
sudo pipework/pipework brbgp1_${SANDBOX_NAME} -i bgp2 mnsandbox${SANDBOX_NAME}_quagga2_1 10.1.0.241/24

# BGP peer2
sudo pipework/pipework brbgp2_${SANDBOX_NAME} -i bgp1 mnsandbox${SANDBOX_NAME}_quagga2_1 10.2.0.240/24
sudo pipework/pipework brbgp2_${SANDBOX_NAME} -i bgp2 mnsandbox${SANDBOX_NAME}_quagga1_1 10.2.0.241/24

# An interface per midolman peer (for single and multiple sessions)
# MM peer1
sudo pipework/pipework brbgp1_${SANDBOX_NAME} -i bgp0 mnsandbox${SANDBOX_NAME}_midolman1_1 10.1.0.1/24

# MM peer2
sudo pipework/pipework brbgp2_${SANDBOX_NAME} -i bgp0 mnsandbox${SANDBOX_NAME}_midolman2_1 10.2.0.1/24

sudo docker exec mnsandbox${SANDBOX_NAME}_quagga0_1 sysctl net.ipv6.conf.all.disable_ipv6=0
sudo docker exec mnsandbox${SANDBOX_NAME}_quagga1_1 sysctl net.ipv6.conf.all.disable_ipv6=0
sudo docker exec mnsandbox${SANDBOX_NAME}_quagga2_1 sysctl net.ipv6.conf.all.disable_ipv6=0
sudo docker exec mnsandbox${SANDBOX_NAME}_midolman1_1 sysctl net.ipv6.conf.all.disable_ipv6=0
sudo docker exec mnsandbox${SANDBOX_NAME}_midolman2_1 sysctl net.ipv6.conf.all.disable_ipv6=0
sudo docker exec mnsandbox${SANDBOX_NAME}_midolman3_1 sysctl net.ipv6.conf.all.disable_ipv6=0
