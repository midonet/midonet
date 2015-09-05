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

# Keep track of the current directory
DEVMIDO_DIR=$(cd $(dirname $0) && pwd)

# Import common functions
source $DEVMIDO_DIR/functions

# Check if run as root
if [[ $EUID -eq 0 ]]; then
    die $LINENO "You cannot run this script as root."
fi

MIDORC=$DEVMIDO_DIR/midorc
if [[ ! -r $MIDORC ]]; then
    die $LINENO "Missing $MIDORC"
fi
source $MIDORC

set -o xtrace

# Hard coded screen name
SCREEN_NAME=mido

# Clean up the remainder of the screen processes
stop_process midonet-cluster
stop_process midolman

# Remove the screen session
SESSION=$(screen -ls | awk -v "pat=[0-9].mido" '$0 ~ pat { print $1 }')
screen -X -S $SESSION quit

# The midolman Java calls keep running after killing the processes
for p in $(ps aux | grep mido | grep java | awk '{print $2}'); do
    sudo kill -9 "$p"
done
