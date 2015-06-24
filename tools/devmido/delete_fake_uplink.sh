#!/usr/bin/env bash

# Copyright 2015 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# This script cleans up the fake uplink created from the
# 'create_fake_uplink.sh' script:
#
#      ./delete_fake_uplink.sh

# Save the top directory and source the functions and midorc
TOP_DIR=$(cd $(dirname "$0") && pwd)
source $TOP_DIR/midorc
source $TOP_DIR/functions

set -x

sudo ip link set dev uplinkbridge down 2> /dev/null
sudo brctl delbr uplinkbridge 2> /dev/null
sudo ip link set veth0 down 2> /dev/null
sudo ip link set veth1 down 2> /dev/null
sudo ip link del veth0 2> /dev/null # This should delete veth1

HOST_NET='172.19.0.0/30'
HOST_IP_1='172.19.0.1'
HOST_IP_2='172.19.0.2'

# Grab the provider router
ROUTER_NAME='MidoNet Provider Router'
ROUTER_ID=$(midonet-cli -e router list | grep "$ROUTER_NAME" | \
    awk '{ print $2 }')
echo "Provider Router: ${ROUTER_ID}"

if [[ -n "$ROUTER_ID" ]]; then
    PORT_ID=$(midonet-cli -e router $ROUTER_ID list port address $HOST_IP_2 \
        net $HOST_NET | awk '{ print $2}')
    if [[ -n "$PORT_ID" ]]; then
        # Remove the port - this should unbind and clear the routes
        echo "Deleting port: $PORT_ID"
        midonet-cli -e delete port $PORT_ID
    fi
fi

TZ_NAME='default_tz'
TZ_ID=$(midonet-cli -e list tunnel-zone name $TZ_NAME | awk '{ print $2 }')
if [[ -n "$TZ_ID" ]]; then
    # Delete the member and the tunnel zone
    echo "Deleting tunnel zone: $TZ_ID"
    midonet-cli -e delete tunnel-zone $TZ_ID
fi

echo "Successfully removed fake uplink"
