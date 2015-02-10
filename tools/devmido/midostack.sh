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

# This script is meant to be sourced from devstack.  It is a wrapper of
# devmido scripts that allows proper exporting of environment variables.
#
# Example:
#    source ./midostack.sh mido                 // Runs mido.sh
#    source ./midostack.sh unmido               // Runs unmido.sh
#    source ./midostack.sh create_fake_uplink   // Runs create_fake_uplink.sh
#    source ./midostack.sh delete_fake_uplink   // Runs delete_fake_uplink.sh

if [[ -n "$1" ]]; then
    CMD=$1
else
    echo "Usage: source midostack.sh <COMMAND>"
    exit -1
fi

# Back up the current options of devstack so that we can change them in here
# as we wish
OLD_OPTS=$(set +o)

# Devstack should set errexit option, but setting here again to make sure
# because this script requires that this option is set
set -o errexit

# Sanity checks - these must be set
MIDONET_DIR=${MIDONET_DIR:?Error \$MIDONET_DIR is not set}

if [[ $CMD -eq "mido" ]]; then

    export SERVICE_HOST=${MIDONET_SERVICE_HOST:?Error \$MIDONET_SERVICE_HOST is not set}
    export API_PORT=${MIDONET_API_PORT:?Error \$MIDONET_API_PORT is not set}

    export TIMESTAMP_FORMAT
    export LOGFILE
    export SCREEN_LOGDIR

elif [[ $CMD -eq "unmido" ]]; then

    # Nothing to export

elif [[ $CMD -eq "create_fake_uplink" -o $CMD -eq "delete_fake_uplink" ]]; then

    export CIDR=${FLOATING_RANGE:?Error \$FLOATING_RANGE is not set}

else
    echo "Error - Unrecognized command: $CMD"
    exit -1
fi

# Run the command
$MIDONET_DIR/tools/devmido/$CMD.sh

# Restore the options
eval "$OLD_OPTS" 2> /dev/null
