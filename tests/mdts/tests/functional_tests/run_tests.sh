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

cd $(dirname $0)

usage()
{
cat << EOF
usage: $0 [OPTIONS] -- [...]

This script runs the QA tests on this machine. If no test is specified all
tests are run.

OPTIONS:
 -h          This help message
 -e TEST     Exclude this test (can be specified multiple times)
 -t TEST     Runs this test(s)
 -l LOG_DIR  Directory where to store results (defaults to ./logs)
 -n SANDBOX  Use this sandbox name to run the tests on (defaults to mdts)
 -g          Do not run gate tests
 -G          Run only gate tests
 -s          Do not run slow tests
 -S          Runs only slow tests
 -x          Do not run flaky tests
 -X          Runs only flaky tests
 [...]       Additional options passed to the nose runner

TEST:
  test_file.py           Runs all the tests in the test file.
  test_file.py:test_name Runs a single test from the test file.

EXAMPLES:
$0 -t test_bridge.py
$0 -t test_bridge.py:test_icmp
$0 -t test_bridge.py:test_icmp -t test_router.py -t test_l2gw.py:test_icmp_from_mn

DEBUGGING:
If you want the test to stop upon an exception and open an interactive session with 
the debugger, just add --pdb (or --ipdb for the interactive version of pdb) on the
additional parameters list.

$0 -l logs -t test_bridge.py -- --pdb

If you want to add a breakpoint on your test, just add a line like this on the python
src code you want the test runner to stop:

import ipdb; ipdb.set_trace()

EOF
}

ATTR=""
ARGS=""  # passed directly to nosetests runner
LOG_DIR="logs-$(date +"%y%m%d%H%M%S")"

while getopts "e:ht:sSv:V:xXr:l:gGn:" OPTION
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
        l)
            LOG_DIR=$OPTARG/
            ;;
        g)
            if [ -z "$ATTR" ]
            then
                ATTR="not gate"
            else
                ATTR="$ATTR and not gate"
            fi
            ;;
        G)
            if [ -z "$ATTR" ]
            then
                ATTR="gate"
            else
                ATTR="$ATTR and gate"
            fi
            ;;
        n)
            export MDTS_SANDBOX_NAME=$OPTARG
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

shift $(( OPTIND - 1 ))

# Avoid masking the exit status of nose with the exit 0 of tee
set -o pipefail
set -x

./runner.py -c nose.cfg $@ --mdts-logs-dir $LOG_DIR ${ATTR:+"-A $ATTR"} $TESTS $ARGS
