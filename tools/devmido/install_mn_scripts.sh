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
# Change MIDO_HOME (used by mm-ctl / mm-dpctl) to point at deps dir

# Script that copies over the necessary files to make the midonet
# scripts available on the host.  It currently assumes the jar files are
# already built but should be improved to build them here if necessary.

# Keep track of the current directory
DEVMIDO_DIR=$(cd $(dirname $0) && pwd)

# Keep track of the midonet root directory
TOP_DIR=$(cd $DEVMIDO_DIR/../../ && pwd)

# Place our executables in /usr/local/bin
MM_CTL="/usr/local/bin/mm-ctl"
MM_DPCTL="/usr/local/bin/mm-dpctl"
MM_TRACE="/usr/local/bin/mm-trace"

# While this feels fragile, for simplicity, just point MIDO_HOME to where
# 'prepare_java' script is since that is the only thing needed from that
# directory by the scripts
MM_HOME=$TOP_DIR/midolman/src/deb/init
MM_JAR=`find $TOP_DIR/midolman/build/installShadow -name "midolman-*-all.jar"`
MM_SCRIPT_DIR=$TOP_DIR/midolman/src/deb/bin

sed -e "s@%MIDO_HOME%@$MM_HOME@" \
    -e "s@%MIDO_JAR%@$MM_JAR@" \
    -e "s@%SCRIPT_DIR%@$MM_SCRIPT_DIR@" \
    $DEVMIDO_DIR/binproxy | tee $MM_CTL $MM_DPCTL $MM_TRACE
chmod +x $MM_CTL $MM_DPCTL $MM_TRACE

# Do the same for midonet-tools
MN_CONF="/usr/local/bin/mn-conf"
MM_METER="/usr/local/bin/mm-meter"
TOOLS_HOME=$TOP_DIR/midonet-tools/src/share
TOOLS_JAR=`find $TOP_DIR/midonet-tools/build/installShadow -name "midonet-tools-*-all.jar"`
TOOLS_SCRIPT_DIR=$TOP_DIR/midonet-tools/src/bin

sed -e "s@%MIDO_HOME%@$TOOLS_HOME@" \
    -e "s@%MIDO_JAR%@$TOOLS_JAR@" \
    -e "s@%SCRIPT_DIR%@$TOOLS_SCRIPT_DIR@" \
    $DEVMIDO_DIR/binproxy | tee $MN_CONF $MM_METER
chmod +x $MN_CONF $MM_METER

echo "install_mn_scripts.sh has successfully completed."
