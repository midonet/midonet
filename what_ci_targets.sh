#!/bin/bash
#
# Recommends targets for CI based on the last commit's contents.
#
# Usage:
#
# ./what_ci_targets.sh (debian|rpm)
#
# Where:
# - DIST is the target distribution when packaging, defaults to debian
#
# Outputs at most two lines explaning the right targets for unit and
# MDTS tests.
#
# A line starting starting with ":gradle" will *always* be output and
# provides a parameter list to pass to a gradle command.  E.g.:
#
#   :gradle clean test -Dexplanation
#
# a user (or script) can drop the first token (:gradle) and use the rest
# in a gradle command to trigger the build.  Note that the -Dexplanation
# will always be added for debug purposes: "explanation" will identify
# the type of buil, and is inocuous to gradle that will ignore it.
#
# A second line starting with ":mdts" *may* be output, identifying MDTS
# targets that can be passed straight to run_tests.sh.  E.g.:
#
#   :mdts -t test_bridge
#
# When the :mdts line is ommitted, we don't consider MDTS tests
# necessary for HEAD.

DIST=$1
if [ "x$DIST" == "x" ]
then
    DIST=debian
fi

FAST_BUILD=":gradle clean assemble testClasses midonet-util:test $DIST"
FULL_BUILD=":gradle clean build $DIST"
FULL_MDTS=":mdts"
FAST_MDTS=":mdts -t test_bridge:test_icmp"

# Various regexes matching files that don't require unit/integration tests
REGEX_NOT_CODE='^(python-midonetclient|docs|misc|tools/devmido|specs|LICENSE|DEVELOPMENT.md|README.md|what_ci_targets.sh)'

# How many unit-test relevant files are changed in this patch
CODE_CHANGES=`git diff-tree --no-commit-id --name-only -r HEAD | grep -v -E '$REGEX_NOT_CODE' | wc -l | xargs`

if [ "$CODE_CHANGES" == "0" ]
then
    # There are no relevant code changes, let't compile, package,
    # and run fast smoke tests, no need to run MDTS
    echo "$FAST_BUILD -DnoRelevantChanges"
    exit 0
fi

if [ "$CODE_CHANGES" == "1" ]
then
    # We do have code changes
    if [[ $(git diff-tree --no-commit-id --name-only -r HEAD build.gradle) ]]
    then
      # Only 1 relevant file has changes, but it's build.gradle, let's
      # ensure that we're just doing a version bump
      LINES_CHANGED=`git diff HEAD~1 build.gradle | grep -E "^\+ " | wc -l | xargs`
      if [ "$LINES_CHANGED" == "1" ] && [ -z $(git diff HEAD~1 build.gradle | grep -E "^\+ " | grep -v midonetVersion) ]
      then
        # Only a version bump, let's make sure that the packages build
        # and install.  Just smoke tests both on unit and MDTS.
        echo "$FAST_BUILD -DversionBump"
        echo "$FAST_MDTS"
        exit 0
      fi

      # More than 1 single line were changed, this is not just a
      # version change, run a full build
      echo "$FULL_BUILD -DbuildChange"
      echo "$FULL_MDTS"
      exit 0

    fi
fi

echo "$FULL_BUILD -DnormalCommit"
echo "$FULL_MDTS"
