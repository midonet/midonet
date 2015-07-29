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

# The first existing directory is used for JAVA_HOME if needed.
JVM_SEARCH_DIRS="/usr/lib/jvm/java-1.8.0-openjdk-amd64 /usr/lib/jvm/java-8-openjdk-amd64 \
                 /usr/lib/jvm/java-8-oracle /usr/lib/jvm/zulu-8-amd64"

check_for_java8() {
    [ "x" = "x$1" ] && return 1
    [ -x "$1" ] || return 1
    $1 -version 2>&1 | grep '^java version' | sed -e 's/^[^"]*"\(.*\)"$/\1/' \
        | grep '^1.8.' >/dev/null 2>&1
}

if [ -n "`which java`" ]; then
        java=`which java`
        # Dereference symlink(s)
        while true; do
            if [ -h "$java" ]; then
                java=`readlink "$java"`
                continue
            fi
            break
        done
        JVM_SEARCH_DIRS="`dirname $java`/../ $JVM_SEARCH_DIRS"
fi
if [ ! -z "$JAVA_HOME" ]; then
    JVM_SEARCH_DIRS="$JAVA_HOME $JVM_SEARCH_DIRS"
fi

oldopts=$-
set +e
JAVA_HOME=
for jdir in $JVM_SEARCH_DIRS; do
    check_for_java8 "$jdir/bin/java"
    if [ $? -eq 0 ]; then
        JAVA_HOME="$jdir"
        break
    fi
done
echo $oldopts | grep 'e' 2>&1 >/dev/null && set -e

if [ -z "$JAVA_HOME" ] ; then
    echo "No suitable JVM found (at least v1.8 required)"
    exit 1
fi

JAVA="$JAVA_HOME/bin/java"
