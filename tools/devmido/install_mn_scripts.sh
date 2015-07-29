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

# Change MIDO_HOME (used by mm-ctl / mm-dpctl) to point at deps dir
DEPS_DIR="$TOP_DIR/midodeps"
AGENT_BUILD_DIR="$TOP_DIR/midolman/build/install/midolman/lib"

rm -rf $DEPS_DIR ; mkdir -p $DEPS_DIR
cp $AGENT_BUILD_DIR/midolman-*.jar  $DEPS_DIR/midolman.jar
cp -r $AGENT_BUILD_DIR $DEPS_DIR/dep

# Place our executables in /usr/local/bin
MM_CTL="/usr/local/bin/mm-ctl"
MM_DPCTL="/usr/local/bin/mm-dpctl"
MN_CONF="/usr/local/bin/mn-conf"
sed -e "s@%DEPS_DIR%@$DEPS_DIR@" \
    -e "s@%TOP_DIR%@$TOP_DIR@" \
    $DEVMIDO_DIR/binproxy | tee $MM_CTL $MM_DPCTL $MN_CONF
chmod +x $MM_CTL $MM_DPCTL $MN_CONF

echo "install_mn_scripts.sh has successfully completed."
