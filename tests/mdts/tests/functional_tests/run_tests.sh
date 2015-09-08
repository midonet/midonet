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
usage: $0 [OPTION] [...]

This script runs the QA tests on this machine. If no test is specified all
tests are run.

OPTIONS:
 -h          This help message
 -e TEST     Exclude this test (can be specified multiple times)
 -t TEST     Runs this test(s)
 -l LOG_DIR  Directory where to store results (defaults to ./logs)
 -r DIR      Use DIR as the Python root (to determine imported modules)
 -s          Runs only fast tests
 -S          Runs only slow tests
 -x          Runs all tests but flaky ones
 -X          Runs only flaky tests
 [...]       Additional options passed to the nose runner

TEST:
  test_file.py           Runs all the tests in the test file.
  test_file.py:test_name Runs a single test from the test file.

EXAMPLES:
$0 -t test_bridge.py
$0 -t test_bridge.py:test_icmp
$0 -t test_bridge.py:test_icmp -t test_router.py -t test_l2gw.py:test_icmp_from_mn
$0 -l logs -t test_bridge.py --pdb

EOF
}

PDIR=../../../
ATTR=""
ARGS=""  # passed directly to nosetests runner
LOG_DIR="logs-$(date +"%y%m%d%H%M%S")"

while getopts "e:ht:sSv:V:xX:r:l:" OPTION
do
    case $OPTION in
        h)
            usage
            exit 0
            ;;
        e)
            ARGS="$ARGS -e $OPTARG"
            ;;
        t)
            # Workarround to define the test without py
            TESTMOD=$(echo $OPTARG | cut -d: -f1)
            TESTCASE=$(echo $OPTARG | cut -d: -f2)
            if ! echo $TESTMOD | grep .py; then
                TEST=$TESTMOD.py
            else
                TEST=$TESTMOD
            fi
            if ! test $TESTMOD == $TESTCASE; then
                TEST=$TEST:$TESTCASE
            fi
            TESTS="$TESTS $TEST"
            ;;
        r)
            PDIR=$OPTARG
            ;;
        l)
            LOG_DIR=$OPTARG/
            ;;
        ?)
            usage
            exit 1
            ;;
        # FIXME: Legacy options, to be removed if not used
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
        *)
            echo star
            ;;
    esac
done

shift $(( OPTIND - 1 ))

# Avoid masking the exit status of nose with the exit 0 of tee
set -o pipefail

echo "sudo PYTHONPATH=$PDIR ./runner.py -c nose.cfg $@ --mdts-logs-dir $LOG_DIR ${ATTR:+\"-A $ATTR\"} $TESTS $ARGS "
sudo PYTHONPATH=$PDIR ./runner.py -c nose.cfg $@ --mdts-logs-dir $LOG_DIR ${ATTR:+"-A $ATTR"} $TESTS $ARGS
