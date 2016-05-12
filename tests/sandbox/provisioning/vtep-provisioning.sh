#!/bin/bash

echo "Provisioning vtep interfaces..."

sudo docker exec mnsandbox${SANDBOX_NAME}_vtep1_1 /etc/init.d/openvswitch-vtep start
sudo docker exec mnsandbox${SANDBOX_NAME}_vtep2_1 /etc/init.d/openvswitch-vtep start

sudo docker exec mnsandbox${SANDBOX_NAME}_vtep1_1 /configure-vtep.sh
#TODO: Move the following to the configuration script.
sudo docker exec mnsandbox${SANDBOX_NAME}_vtep1_1 pkill ovs-vtep
sudo docker exec mnsandbox${SANDBOX_NAME}_vtep1_1 sleep 3
sudo docker exec mnsandbox${SANDBOX_NAME}_vtep1_1 /usr/share/openvswitch/scripts/ovs-vtep --log-file=/var/log/openvswitch/ovs-vtep.log --pidfile=/var/run/openvswitch/ovs-vtep.pid --detach vtep0

sudo docker exec mnsandbox${SANDBOX_NAME}_vtep2_1 /configure-vtep.sh
#TODO: Move the following to the configuration script.
sudo docker exec mnsandbox${SANDBOX_NAME}_vtep2_1 pkill ovs-vtep
sudo docker exec mnsandbox${SANDBOX_NAME}_vtep2_1 sleep 3
sudo docker exec mnsandbox${SANDBOX_NAME}_vtep2_1 /usr/share/openvswitch/scripts/ovs-vtep --log-file=/var/log/openvswitch/ovs-vtep.log --pidfile=/var/run/openvswitch/ovs-vtep.pid --detach vtep0