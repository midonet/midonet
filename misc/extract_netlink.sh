#!/bin/sh

# Copyright 2014 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# This will checkout a clean copy of the midonet repository into a temporary
# directory and then it will clean the history up by removing part of the commits not
# directly related to the nelink + odp library and then removing empty
# remaining empty commits that might appear.
# It will also remove the empty merge commits that might appear and initial roots
# that are remains of the original merges of the previous repositories
#
# The list of files/folders not directly related to the netlink code library.
UNUSED_FOLDERS="src conf conf2 err.txt \
	pom.xml README CHANGELOG CHANGELOG.TXT NEWS NEWS.TXT README.md \
	.classpath lib-native .gitignore .project debian \
	docs \
	midolmanj \
	midolman \
	midolmanj-mgmt \
	midonet-api \
	midonet-client \
	midonet-functional-tests \
	misc \
	midonet-util/src/main/java/org/midonet/{cache,config,hamcrest,midolman,packets,remote,tools} \
	midonet-util/src/main/java/org/midonet/util/{collections,jmx,lock,process,ssh} \
	midonet-util/src/main/java/org/midonet/util/{Percentile,Sampler,StringUtil,SystemHelper}.java \
	midonet-util/src/main/java/org/midonet/util/functors/{CollectionFunctors,UnaryFunctor,TreeNode,TreeNodeFunctors,Callback0,Callback1,Callback2,Callback3}.java \
	midonet-util/src/main/java/org/midonet/sdn/flows/{FlowManagerHelper,PacketMatch,FlowManager,WildcardFlow,WildcardFlowIndex},java \
	midonet-util/src/main/java/org/midonet/sdn/flowa/{NetlinkFlowTable,WildcardSpec,MidoMatch,WildcardMatch,WildcardMatches,WildcardMatchImpl,ProjectedWildcardMatch}.java \
	midonet-util/src/main/java/org/midonet/Test.java \
	midonet-util/src/test/java/org/midonet/{config,packets,remote,sdn} \
	midonet-util/src/test/java/org/midonet/util/{TestPercentile,TestStringUtil}.java \
	midonet-util/src/test/java/org/midonet/util/process/TestProcessHelper.java \
	midonet-util/src/test/resources/logback.xml \
	midonet-util/{pom.xml,.classpath,.project,README} \
	midonet-smoketest
"

# The target work folder
WORK_FOLDER="`pwd`/../../extract-netlink-workspace"

echo "Cloning the repository into $WORK_FOLDER"
rm -rf "${WORK_FOLDER}"
mkdir -p "${WORK_FOLDER}"
git clone https://github.com/midokura/midonet.git "${WORK_FOLDER}"

pushd "${WORK_FOLDER}"
echo "Removing the origin remote and non master tags (press a key):"
read
git remote rm origin
git branch -a
git tag -d Abaza-11.12-midolmanj
git tag -d Abaza-11.12-midolmanj-mgmt
git tag -d babuza-12.06.0


echo "Pruning the tree of unwanted files/commits (press a key):"
read
# this will remove the non matching files/folder and any empty commits that
# remain based on that
git filter-branch -f \
    --index-filter "git rm -r -f --cached --ignore-unmatch ${UNUSED_FOLDERS}" \
    --tag-name-filter cat \
    --prune-empty \
    -- --all

# this will change the parents of merge commits to be independent
# (as in if a merge commits has parent1 and parent2 and parent1 is also a parent2
# it will replaced with only a parent1
# and it will also remove empty commits (with only one parent) generated along the way
git filter-branch -f \
    --parent-filter "sed s/-p//g | xargs git show-branch --independent | xargs -n 1 echo -p | xargs" \
    --tag-name-filter cat \
    --prune-empty \
    -- --all

# clean the backup refs
git update-ref -d refs/original/refs/heads/master

# one more removal of empty commits (not entirely sure this is needed)
git filter-branch -f \
    --commit-filter 'git_commit_non_empty_tree "$@"' \
    -- --all

# clean the backup refs
git update-ref -d refs/original/refs/heads/master

# grafting the history to start after 1 Feb 2012

NEW_ROOT_PARENT=`git log --before="Feb 1 2012" --pretty=%H | head -n 1`
echo "Found the parent of the new root graft ${NEW_ROOT_PARENT}"

NEW_ROOT=`git log --ancestry-path ${NEW_ROOT_PARENT}..master --pretty=%H | tail -n 1`

echo "Found the new root graft ${NEW_ROOT}"
git show ${NEW_ROOT}

echo "Grafting the repository root to ${NEW_ROOT}" && read
echo ${NEW_ROOT} > .git/info/grafts
git filter-branch -- --all
# clean the backup refs
git update-ref -d refs/original/refs/heads/master

# the following is required so we can remove the old commits that might not be deleted
echo "Pruning (garbage collecting the repository):" && read
git reflog expire --expire=now
git gc --prune=now

echo "Adding target remote"
git remote add target https://github.com/midokura/odp.git
git push -f target master:master
popd
