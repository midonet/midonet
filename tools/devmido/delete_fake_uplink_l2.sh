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
# 'create_fake_uplink.sh' script.  To run:
#
#      ./delete_fake_uplink.sh

# Save the top directory and source the functions and midorc
TOP_DIR=$(cd $(dirname "$0") && pwd)

source $TOP_DIR/midorc
source $TOP_DIR/functions

set -x

# Get the host id of the devstack machine
HOST_ID=$(midonet-cli -e host list | awk '{ print $2 }')
if [[ -z "$HOST_ID" ]]; then
    echo "Host ID could not be found.  No binding should exist. Existing..."
    exit 0
fi

# Find the binding
PORT_ID=$(midonet-cli -e host $HOST_ID list binding interface veth1 | \
     awk '/port/ { for (x=1;x<=NF;x++) if ($x~"port") print $(x+1) }')
if [[ -n "$PORT_ID" ]]; then
    midonet-cli -e delete port $PORT_ID
fi

sudo ip link set veth0 down 2> /dev/null
sudo ip link set veth1 down 2> /dev/null
sudo ip link del veth0 2> /dev/null # This should delete veth1

TZ_NAME='default_tz'
TZ_ID=$(midonet-cli -e list tunnel-zone name $TZ_NAME | awk '{ print $2 }')
if [[ -n "$TZ_ID" ]]; then
    # Delete the member and the tunnel zone
    echo "Deleting tunnel zone: $TZ_ID"
    midonet-cli -e delete tunnel-zone $TZ_ID
fi

echo "Successfully removed fake uplink"
