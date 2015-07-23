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
 -e TEST     Exclude this test (can be specified multiple times)
 -t TEST     Runs this test(s)
 -s          Runs only fast tests
 -S          Runs only slow tests
 -x          Runs all tests but flaky ones
 -X          Runs only flaky tests
 -r DIR      Use DIR as the Python root (to determine imported modules)

TEST:
  test_file           Runs all the tests in the test file.
  test_file:test_name Runs a single test from the test file.

EXAMPLES:
$0 -t test_bridge
$0 -t test_bridge:test_icmp
$0 -t test_bridge:test_icmp -t test_router -t test_l2gw:test_icmp_from_mn

Fast tests
$0 -s

EOF
}

DIR=../../..
ATTR=""
ARGS=""  # passed directly to nosetests runner
while getopts "e:ht:sSv:V:xX:r:" OPTION
do
    case $OPTION in
        h)
            usage
            exit 0
            ;;
        e)
            ARGS="$ARGS -e $OPTARG"
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
        r)
            PDIR=$OPTARG
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

# Avoid masking the exit status of nose with the exit 0 of tee
set -o pipefail

sudo PYTHONPATH=$PDIR ./runner.py -c nose.cfg ${ATTR:+"-A $ATTR"} $TESTS $ARGS 2>&1 | tee nosetests.`date +%Y%m%d-%H%M`.log
