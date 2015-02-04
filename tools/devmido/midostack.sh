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

# Share the same logging locations
export TIMESTAMP_FORMAT
export LOGFILE
export SCREEN_LOGDIR

# Keep the midonet API URL consistent
export SERVICE_HOST=$MIDONET_SERVICE_HOST
export API_PORT=$MIDONET_API_PORT

# Set auth variables for midonet-cli
export MIDO_USER=$Q_ADMIN_USERNAME
export MIDO_PROJECT_ID=$ADMIN_TENANT_ID
export MIDO_PASSWORD=$ADMIN_PASSWORD

$MIDONET_DIR/tools/devmido/mido.sh
