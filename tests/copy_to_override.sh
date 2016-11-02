#!/bin/bash
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

# Copy the latest packages present on the tree to the override
# specified by command line.
# e.g: $ tests/copy_to_override.sh override_v2

if [ $# -ne 1 ]; then
    echo "Usage: $0 override_v2"
    exit 1
fi

cd $(dirname "$(readlink -f $0)")/..

MIDOLMAN_PATH=midolman/build/packages
CLUSTER_PATH=midonet-cluster/build/packages
TOOLS_PATH=midonet-tools/build/packages
CLIENT_PATH=python-midonetclient

mkdir -p tests/sandbox/$1/packages
cp $(ls $MIDOLMAN_PATH/*deb | tail -n1) tests/sandbox/$1/packages
cp $(ls $CLUSTER_PATH/*deb | tail -n1) tests/sandbox/$1/packages
cp $(ls $TOOLS_PATH/*deb | tail -n1) tests/sandbox/$1/packages
cp $(ls $CLIENT_PATH/*deb | tail -n1) tests/sandbox/$1/packages
