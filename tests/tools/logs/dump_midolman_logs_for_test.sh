#!/usr/bin/env bash

# Copyright 2014 Midokura SARL
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

marker_start=$1
marker_end=$2


for d in /var/log/midolman.*; do
    MIDOLMAN_LOG_FILE=$d/midolman.log
    cd $d
    [ -f $MIDOLMAN_LOG_FILE ] && {
        echo ================================
        echo $MIDOLMAN_LOG_FILE
        echo ================================

        #cat  $MIDOLMAN_LOG_FILE
        marker_start=${marker_start//\"/}
        marker_end=${marker_end//\"/}

        sed -n "/$marker_start/, /$marker_end/ p" $MIDOLMAN_LOG_FILE
    }
    cd $OLDPWD
done

exit 0 # make sure that nose plugin doesn't die

