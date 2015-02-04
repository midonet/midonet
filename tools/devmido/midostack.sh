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
# mido.sh that allows proper exporting of environment variables to mido.sh.

# Back up the current options of devstack so that we can change them in here
# as we wish
OLD_OPTS=$(set +o)

# Devstack should set errexit option, but setting here again to make sure
# because this script requires that this option is set
set -o errexit

# Sanity checks - these must be set
MIDONET_DIR=${MIDONET_DIR:?Error \$MIDONET_DIR is not set}
export SERVICE_HOST=${MIDONET_SERVICE_HOST:?Error \$MIDONET_SERVICE_HOST is not set}
export API_PORT=${MIDONET_API_PORT:?Error \$MIDONET_API_PORT is not set}

# Share the same logging locations
export TIMESTAMP_FORMAT
export LOGFILE
export SCREEN_LOGDIR

$MIDONET_DIR/tools/devmido/mido.sh

# Restore the options
eval "$OLD_OPTS" 2> /dev/null
