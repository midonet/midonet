#!/bin/bash
#
# Recommends targets for CI based on the last commit's contents.  The
# output to stdout can be used as a parameter list to gradle.
#
# The list of targets will include a -Dxxxxx parameter with a short
# description of the choice we're making.  This is for debug purposes,
# and has no effect on the gradle builds.
#
# Usage:
#
# ./what_ci_targets.sh (debian|rpm)
#
# Where:
# - DIST is the target distribution when packaging, defaults to debian
#
# Output:

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
