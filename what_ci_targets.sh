#!/bin/bash
#
# Recommends targets for CI based on the last commit's contents.
#
# This script will examine the changes made by HEAD and, based on
# them, recommend a set of targets for Unit, MDTS and Tempest tests.
# Note that the script will *not* verify correctness of the patch.
#
# Usage:
#
# ./what_ci_targets.sh (debian|rpm)
#
# Outputs at most two lines explaning the right targets for unit and
# MDTS tests.
#
# A line starting with ":gradle" will *always* be output and provides a
# parameter list to pass to a gradle command.  E.g.:
#
#   :gradle clean test

# A line starting with :type will explain the type of change detected by
# the script.
#
# A second line starting with ":mdts" *may* be output, identifying MDTS
# targets that can be passed straight to run_tests.sh.  E.g.:
#
#   :mdts -t test_bridge
#
# When the :mdts line is ommitted, we don't consider MDTS tests
# necessary for HEAD.
#
# A third tag :tempest will indicate whether Tempest tests are
# recommended to verify HEAD.
#
# Users may execute the script, drop the :gradle / :mdts / :tempest tag,
# and pass the remainder of each line to the ./gradlew or
#
# ./tests/mdts/functional_tests/run_tests.sh executables.

DIST=$1
if [ "x$DIST" == "x" ]
then
    DIST=debian
fi

if [ "$DIST" != "debian" ] && [ "$DIST" != "rpm" ]
then
    echo "$DIST is not a valid distribution (use debian or rpm)"
    exit -1
fi

DESC_VER_BUMP=":type Version bump"
DESC_NORMAL=":type Normal changeset"
DESC_NO_CHANGES=":type Changeset without changes relevant for testing"
FAST_BUILD=":gradle clean assemble testClasses midonet-util:test $DIST"
FULL_BUILD=":gradle clean build $DIST"
FULL_MDTS=":mdts"
FAST_MDTS=":mdts -t test_bridge:test_icmp"
FULL_TEMPEST=":tempest"

# Various regexes matching files that don't require unit/integration tests
REGEX_FOR_UNIT='^(python-midonetclient|docs|misc|tools/devmido|specs|LICENSE|DEVELOPMENT.md|README.md|what_ci_targets)'
REGEX_FOR_MDTS='^(docs|misc|tools/devmido|specs|LICENSE|DEVELOPMENT.md|README.md|what_ci_targets)'

# How many unit-test relevant files are changed in this patch
FOR_UNIT=`git diff-tree --no-commit-id --name-only -r HEAD | grep -Ev "$REGEX_FOR_UNIT" | wc -l | xargs`
FOR_MDTS=`git diff-tree --no-commit-id --name-only -r HEAD | grep -Ev "$REGEX_FOR_MDTS" | wc -l | xargs`

let TOTAL="$FOR_UNIT + $FOR_MDTS"
if [ "$TOTAL" == 0 ]
then
    echo "$DESC_NO_CHANGES"
    echo "$FAST_BUILD"
    exit 0
fi

if [ "$FOR_UNIT" == "1" ] && [ $(git diff-tree --no-commit-id --name-only -r HEAD build.gradle) ]
then
  # Only 1 relevant file has changes, but it's build.gradle, let's ensure that
  # we're just doing a version bump
  LINES_REMOVED=`git diff HEAD~1 build.gradle | grep -E "^\- " | wc -l | xargs`
  LINES_ADDED=`git diff HEAD~1 build.gradle | grep -E "^\+ " | wc -l | xargs`
  CHANGE=`git diff HEAD~1 build.gradle | grep -E "^\+ " | grep -v midonetVersion`
  if [ "$LINES_REMOVED" == "1" ] && [ "$LINES_ADDED" == "1" ] && [ "x$CHANGE" == "x" ]
  then
    echo "$DESC_VER_BUMP"
    echo "$FAST_BUILD"
    echo "$FAST_MDTS"
    exit 0
  fi
fi

echo "$DESC_NORMAL with $FOR_UNIT changes relevant for unit tests and $FOR_MDTS for mdts"
test "$FOR_UNIT" -gt 0 && echo "$FULL_BUILD"
test "$FOR_MDTS" -gt 0 && echo "$FULL_MDTS"
test "$FOR_MDTS" -gt 0 && echo "$FULL_TEMPEST"
