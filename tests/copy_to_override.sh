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
# e.g: $ copy_to_override.sh override_v2

if [ $# -ne 1 ]; then
    echo "Usage: $0 override_v2"
    exit 1
fi
OVERRIDE=$1

cd $(dirname $0)

REPO_ROOT="$PWD/.."

TARGET="$PWD/sandbox/$OVERRIDE/packages"
mkdir -p $TARGET

DEB_PATHS="\
 $REPO_ROOT/midolman/build/packages \
 $REPO_ROOT/midonet-cluster/build/packages \
 $REPO_ROOT/midonet-tools/build/packages \
 $REPO_ROOT/python-midonetclient \
"

echo "Copying build packages to override:"

for DEB_PATH in $DEB_PATHS;
do
    cp --verbose $(ls $DEB_PATH/*deb | tail -n1) $TARGET
done

DEB_NAMES="\
 midolman \
 midonet-cluster \
 midonet-tools \
 python-midonetclient \
"

echo "Copying workspace packages to override:"

RESULT=0

for DEB_NAME in $DEB_NAMES;
do
    if ( ls $REPO_ROOT/$DEB_NAME*.deb >& /dev/null ); then
        cp --verbose $REPO_ROOT/$DEB_NAME*.deb $TARGET
    fi

    if [ "$(ls -la $TARGET/$DEB_NAME*.deb 2> /dev/null | wc -l)" -ne "1" ]; then
        echo "WARNING: Too many '$DEB_NAME' packages in override $TARGET:"
        ls -la $TARGET/$DEB_NAME*.deb
        RESULT=1
    fi
done

exit $RESULT
