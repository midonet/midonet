#!/bin/bash

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

usage()
{
cat << EOF
usage: $0 [OPTION]...

This script runs the QA tests on this machine. If no test is specified all
tests are run.

OPTIONS:
 -h          This help message
 -t TEST     Runs this test(s)
 -s          Runs only fast tests
 -S          Runs only slow tests
 -v VERSION  Runs tests compatible with this and following MidoNet versions
 -V VERSION  Runs only tests compatible with this MidoNet version
 -x          Runs all tests but flaky ones
 -X          Runs only flaky tests

TEST:
  test_file           Runs all the tests in the test file.
  test_file:test_name Runs a single test from the test file.

EXAMPLES:
$0 -t test_bridge
$0 -t test_bridge:test_icmp
$0 -t test_bridge:test_icmp -t test_router -t test_l2gw:test_icmp_from_mn

Fast tests
$0 -s

Tests compatible with v1.2.0, v1.2.1, v1.3.0 ...
$0 -v v1.2.0

Tests compatible only with v1.2.1
$0 -V v1.2.1

Fast tests compatible with v1.2.1 and onwards
$0 -s -v v1.2.1
EOF
}

ATTR=""
while getopts ":ht:sSv:V:xX" OPTION
do
    case $OPTION in
        h)
            usage
            exit 0
            ;;
        s)
            if [ -z "$ATTR" ]
            then
                ATTR="not slow"
            else
                ATTR="$ATTR and not slow"
            fi
            ;;
        S)
            if [ -z "$ATTR" ]
            then
                ATTR="slow"
            else
                ATTR="$ATTR and slow"
            fi
            ;;
        t)
            TEST=$OPTARG
            if [[ $TEST != mdts.tests.functional_tests* ]]
            then
                TEST="mdts.tests.functional_tests.$TEST"
            fi

            TESTS="$TESTS $TEST"
            ;;
        v)
            if [ -z "$ATTR" ]
            then
                ATTR="(version >= \"$OPTARG\")"
            else
                ATTR="$ATTR and (version >= \"$OPTARG\")"
            fi
            ;;
        V)
            if [ -z "$ATTR" ]
            then
                ATTR="(version == \"$OPTARG\")"
            else
                ATTR="$ATTR and (version == \"$OPTARG\")"
            fi
            ;;
        x)
            if [ -z "$ATTR" ]
            then
                ATTR="not flaky"
            else
                ATTR="$ATTR and not flaky"
            fi
            ;;
        X)
            if [ -z "$ATTR" ]
            then
                ATTR="flaky"
            else
                ATTR="$ATTR and flaky"
            fi
            ;;
        ?)
            usage
            exit 1
            ;;
        *)
            echo star
            ;;
    esac
done

shift $(($OPTIND - 1))
if [ "$#" -ne 0 ]
then
    usage
    exit 1
fi

sudo PYTHONPATH=../../../ ./runner.py -c nose.cfg ${ATTR:+"-A $ATTR"} $TESTS 2>&1 | tee nosetests.`date +%Y%m%d-%H%M`.log
