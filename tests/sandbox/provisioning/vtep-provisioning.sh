#!/bin/bash

echo "Provisioning vtep interfaces..."

sudo docker exec mnsandboxmdts_vtep1_1 /etc/init.d/openvswitch-vtep start
sudo docker exec mnsandboxmdts_vtep2_1 /etc/init.d/openvswitch-vtep start

sudo docker exec mnsandboxmdts_vtep1_1 /configure-vtep.sh
sudo docker exec mnsandboxmdts_vtep2_1 /configure-vtep.sh
