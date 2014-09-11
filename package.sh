#!/bin/bash
# vim: fdm=marker foldmarker={,} ts=2 ss=2 ff=unix tw=72
# ------------------------------------------------------
# Midonet packaging script
# ------------------------------------------------------

# Main project name
project_name="midonet"

# Space separated, these are expected to contain a package.sh script that takes
# two params: (deb|rhel) version-tag
build_packages=""

usage() {
    echo -e "\n\e[1;33mMidonet release script\e[m\n\n\
Commands:\n
- package [deb|rhel] <destination-dir> [skip]: this will extract the
  version number from the top-level pom.xml, and package for this version.
  If 'skip' is added at the end, it will skip confirmation upon resetting
  the repository to the currently checked out version.
- prepare_rc <python-midonetclient-ref> <rc-number>: this will update the
  submodule version to the given one, update all the versions in the pom.xml
  files and commit the changes for your review.
- prepare_hotfix <python-midonetclient-ref> <hotfix_number>: same thing, but
  for a hotfix.
- prepare_final <python_midonetclient-ref>: same, but for a final version.

Note that we're assuming the following transitions. Any other combination will
very likely cause foul packages:
- snapshot -> rc, e.g.: 1.2.0-SNAPSHOT -> 1.2.0-rc1
- rc -> final, e.g.: 1.2.4-rc3 -> 1.2.4
- final -> hotfix, e.g.: 1.2.0 -> 1.2.0.hf5
" >&2
    exit 1
}

# Usage: stage"msg"
stage() {
  echo -e "\e[1;33m------------------------\e[m"
  echo -e "\e[1;33m$1\e[m"
  echo -e "\e[1;33m------------------------\e[m"
}

# Usage: log "msg"
log() {
  echo -e "> \e[0;33m$1\e[m \n"
}

# Usage: log "msg"
error() {
  echo -e "> \e[0;31m$1\e[m \n"
}

# Usage: confirm <message> <exit_on_no>
# Will ask <message>, wait until the user selects Yes or No, if user
# says No, then this will exit entirely if <exit_on_no> == true,
# otherwise will just return -1. If Yes, it will return 0
confirm() {
  echo -e "$1"
  select want in Yes No ; do
    if [ "$want" == "Yes" -o "$want" == "No" ] ; then
      break
    fi
  done
  if [ "$want" != "Yes" ] ; then
    if [ "$2" ] ; then
      exit -1
    fi
    return -1
  fi
  return 0
}

ensure_dst_dir() {
  if [ "$1" == "" ]
  then
    error "Please specify destination dir"
    exit -1
  fi
  mkdir -p $1
}

# Will wipe off everything in the current dir, resetting state to the
# current git head. It will ask for user confirmation unless "skip" is
# provided as the first param.
clean_clone() {
  log "Cleaning to revision: \e[0;32m`git log --pretty=format:'%h %d %s <%an>' -1`\e[m"
  git clean -dxn
  git checkout .
  if [ "$1" == "skip" ] ; then
    log "Skipping cleanup confirmation"
  else
    confirm "\n\e[1;31m>> WARNING!! continue cleanup?\e[m" true
  fi
  git clean -dxf
  log "Clone is clean"
}

# Get the current revision of all the git submodules
git_submodule_setup() {
  log "Fetching git submodules.."
  git submodule init
  git submodule update
}

get_pom_version() {
  pom=$1
  mvnver=`grep '<version>.*</version>' $pom | head -1 | sed -e 's/^\s*<version>\(.*\)<\/version>\s*$/\1/'`
  echo $mvnver
}

get_curr_version() {
  mvnV=`get_pom_version pom.xml`
  echo $mvnV | sed 's/-.*//g'
}

build_midonet_maven() {
  log "Bulding MidoNet"
  mvn -q -DskipTests $@ clean package
}

collect_midonet_debs() {
  destdir=$1
  mv midolman/target/*.deb $destdir
  mv midonet-api/target/*.deb $destdir
}

collect_midonet_rpms() {
  destdir=$1
  find midolman/target/rpm/ | grep rpm$ | while read pkg ; do
    mv $pkg $destdir
  done
  find midonet-api/target/rpm/ | grep rpm$ | while read pkg ; do
    mv $pkg $destdir
  done
}

# Usage: do_prepackage <destination_dir> <sha>
# Cleans the repo, ensures that the destination dir is both given and exists,
# and resets the submodules
do_prepackage() {
  log "Preparing for packaging.."
  clean_clone $2
  ensure_dst_dir $1
  git_submodule_setup
}

# Usage: do_package_deb <destination-dir> [skip]
# if "skip" is provided at the second parameter, it will reset the git
# repo without user confirmation.
do_package_deb() {

  do_prepackage $@

  log "DEBIAN packaging.."

  destdir=$1
  pkgver=`get_pom_version pom.xml`
  log "Building debian packages, version: $pkgver destination: $destdir"

  for pkg in $build_packages ; do
    pushd "$pkg" > /dev/null
    ./package.sh deb $pkgver
    popd > /dev/null
    cp $pkg/build/debian/$pkg* $destdir
  done

  build_midonet_maven
  collect_midonet_debs $destdir

  log "DONE"
}

# Usage: do_package_rhel <destination-dir> [skip]
# if "skip" is provided at the second parameter, it will reset the git
# repo without user confirmation.
do_package_rhel() {

  do_prepackage $@

  log "RHEL packaging.."

  destdir=$1
  pkgver=`get_pom_version pom.xml`
  log "Building rpm packages, version $pkgver, destination $destdir"
  for pkg in $build_packages ; do
    pushd "$pkg" > /dev/null
    ./package.sh rhel $pkgver
    popd > /dev/null
    cp $pkg/build/rhel/$pkg*.rpm $destdir
  done

  rpmver=`echo $pkgver | sed -e 's/-.*//g'` # take the 1.3.0 bit from 1.2.3-*
  rpmrel=`echo $pkgver | sed -e 's/.*-//g'` # take the bit after - if exists
  if [ "$rpmver" == "$rpmrel" ]            # there was nothing after -
   then
      log "Looks like this is a FINAL release for $rpmver"
       rpmrel="1.0" # a final
   else
      log "Looks like this is a non final release for $rpmver, $rpmrel"
      TIMESTAMP=`date +%Y%m%d%H%M`
      prever=`echo $pkgver | sed -e "s/^.*-//g" | sed -e "s/SNAPSHOT/$TIMESTAMP/g"`
      rpmrel="0.1.$prever" # a pre release
  fi

  log "Building maven projects"
  build_midonet_maven -Drpm \
      -Dmido.rpm.release=$rpmrel \
      -Dmido.rpm.version=$rpmver
  collect_midonet_rpms $destdir

  log "DONE"
}

# Usage: do_prepare <python_client_revision> <version-appendix>
do_prepare() {

  clientVer=$1
  git_submodule_setup
  log "Updating python_midonetclient to $clientVer"
  pushd python-midonetclient > /dev/null
  git fetch origin
  git checkout $clientVer
  popd > /dev/null

  # current version, removes -SNAPSHOT if present
  currVer=`get_curr_version pom.xml`
  nextVer="$currVer$2"
  stage "Updating submodules and Maven versions from $currVer to $nextVer"
  clean_clone

  to_version $nextVer

}

# Update all pom files with the given version number.
#
# Usage: to_version $TO_VER
# Example: to_version 1.4.2-SNAPSHOT
to_version() {
  mvnVer=`get_pom_version pom.xml`
  nextVer=$1
  log "Updating MidoNet poms from $mvnVer to $nextVer"
  find . -maxdepth 3 -name pom.xml | \
    while read pom ; do
      sed -i -e "s/$mvnVer/$nextVer/g" $pom
    done
    git commit -asm "Updating client module and poms for $nextVer"
  log "The commit is ready to send for review:
  - verify changes with 'git log -1 HEAD~1'
  - push for review to the appropriate branch"
}

COMMAND="$1"
shift

set -e
case $COMMAND in
  deb) do_package_deb $@ ;;
  rhel) do_package_rhel $@ ;;
  prepare_final) do_prepare $@ ;;
  prepare_rc) do_prepare $1 "-rc$2" ;;
  prepare_hotfix) do_prepare $1 ".hf$2" ;;
  to_version) to_version $1 ;;
  *) usage ;;
esac
