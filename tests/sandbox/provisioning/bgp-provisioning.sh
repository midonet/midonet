#!/bin/bash
set -x

function setup_interface {
    BRIDGE=$1
    IFACE=$2
    CONTAINER=$3
    IPV4_ADDRESS=$4
    IPV6_ADDRESS="$5"

    sudo pipework/pipework $BRIDGE -i $IFACE $CONTAINER $IPV4_ADDRESS
    if [[ ! -z $IPV6_ADDRESS ]]; then
        sudo docker exec -it $CONTAINER ip a add $IPV6_ADDRESS dev $IFACE
        sudo docker exec $CONTAINER bash -c "echo 1 > /proc/sys/net/ipv6/conf/$IFACE/forwarding"
    fi
}

echo "Provisioning bgp interfaces..."

# Cleaning previous bridges
sudo ip link set dev brbgp0_${SANDBOX_NAME} down
sudo ip link set dev brbgp1_${SANDBOX_NAME} down
sudo ip link set dev brbgp2_${SANDBOX_NAME} down
sudo ip link del brbgp0_${SANDBOX_NAME}
sudo ip link del brbgp1_${SANDBOX_NAME}
sudo ip link del brbgp2_${SANDBOX_NAME}

# One quagga (master) advertising "inet" networks connected to two
# quaggas (peers) which will do the peering with midolman
setup_interface brbgp0_${SANDBOX_NAME} bgp0 mnsandbox${SANDBOX_NAME}_quagga0_1 10.10.0.1/24 2001:10::1/72
setup_interface brbgp0_${SANDBOX_NAME} bgp0 mnsandbox${SANDBOX_NAME}_quagga1_1 10.10.0.2/24 2001:10::2/72
setup_interface brbgp0_${SANDBOX_NAME} bgp0 mnsandbox${SANDBOX_NAME}_quagga2_1 10.10.0.3/24 2001:10::3/72

# Two interfaces per quagga peer (for single and multiple sessions)
# BGP peer1
setup_interface brbgp1_${SANDBOX_NAME} bgp1 mnsandbox${SANDBOX_NAME}_quagga1_1 10.1.0.240/24 2001:1::240/72
setup_interface brbgp1_${SANDBOX_NAME} bgp2 mnsandbox${SANDBOX_NAME}_quagga2_1 10.1.0.241/24 2001:1::241/72

# BGP peer2
setup_interface brbgp2_${SANDBOX_NAME} bgp1 mnsandbox${SANDBOX_NAME}_quagga2_1 10.2.0.240/24 2001:2::240/72
setup_interface brbgp2_${SANDBOX_NAME} bgp2 mnsandbox${SANDBOX_NAME}_quagga1_1 10.2.0.241/24 2001:2::241/72

# An interface per midolman peer (for single and multiple sessions)
# MM peer1
setup_interface brbgp1_${SANDBOX_NAME} bgp0 mnsandbox${SANDBOX_NAME}_midolman1_1 10.1.0.1/24

# MM peer2
setup_interface brbgp2_${SANDBOX_NAME} bgp0 mnsandbox${SANDBOX_NAME}_midolman2_1 10.2.0.1/24
