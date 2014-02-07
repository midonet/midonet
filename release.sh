#!/bin/bash

project_name="midonet"
maven_projects="."

usage() {
    echo "Usage: $(basename $0) deb|rhel \n" >&2
    exit 1
}

git_setup() {
    echo "Fetching git submodules"
    git submodule init
    git submodule update
}

version_git_to_deb() {
    gver=$1
    echo $gver | sed -e 's/-/~/'
}

version_git_to_rpm() {
    gver=$1
    # the expression below translates:
    #     X.Y.Z-prerelease-tag --> X.Y.Z-0.1.prerelease-tag
    #     X.Y.Z                --> X.Y.Z-1.0
    echo $gver | sed -e 's/-\(.\+\)/-0.1.\1/' | sed -e 's/^\([^-]\+\)$/\1-1.0/'
}

version_git_to_rpm_version() {
    gver=$1
    r_ver_rel=`version_git_to_rpm $gver`
    echo $r_ver_rel | sed -e 's/-.*$//'
}

version_git_to_rpm_release() {
    gver=$1
    r_ver_rel=`version_git_to_rpm $gver`
    echo $r_ver_rel | sed -e 's/^.*-//'
}

fail_if_final_release() {
    echo $1 | grep -- '-' >/dev/null || exit 1
}

get_pom_version() {
    pom=$1
    mvnver=`grep '<version>.*</version>' $pom | head -1 | sed -e 's/^\s*<version>\(.*\)<\/version>\s*$/\1/'`
    echo $mvnver
}

check_mvn_version() {
    submod=$1
    ver=$2
    pom=$1/pom.xml
    mvnver=`get_pom_version $pom`
    if [ "$mvnver" != "$ver" ] ; then
        echo "Maven version mismatch in $pom: found=[$mvnver] expected=[$ver]"
        fail_if_final_release $ver
    fi
}

build_midonet_maven() {
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

# Params: pkgVer isFinal
check_git_tags() {
    pkgver=$1
    isFinal=$2
}

# Params: isFinal
do_package_deb() {
    echo "Building for debian"
    git_setup

    pkgver=$1
    isFinal=false
    if [ "$pkgver" == "" ]
    then
    	echo "Reading version number from git"
    	pkgver=`git describe`
        isFinal=true
    else
        echo "Enforcing non-final version number $pkgver"
    fi

    echo "Package version: $pkgver, is final: $isFinal"
    check_git_tags $pkgver $isFinal

    # drop the leading 'v' used in the git tags
    pkgver=`echo $pkgver | sed -e s/^v//`

    echo "Checking version numbers in maven project"
    check_mvn_version . $pkgver

    destdir="$project_name-$pkgver-deb"
    echo "Packages will be placed in $destdir"
    mkdir $destdir

    echo "Building maven projects"
    build_midonet_maven
    collect_midonet_debs $destdir

    echo "DONE"
}

do_package_rhel() {
    isFinal=$1

    echo "Building for RHEL"
    git_setup

    echo "Reading version number from git"
    pkgver=`git describe`

    echo "Package version: $pkgver, isFinal? $isFinal"
    check_git_tags $pkgver $isFinal

    # drop the leading 'v' used in the git tags
    pkgver=`echo $pkgver | sed -e s/^v//`

    echo "Checking version numbers in maven project"
    check_mvn_version . $pkgver

    destdir="$project_name-$pkgver-rhel"
    echo "Packages will be placed in $destdir"
    mkdir $destdir

    echo "Building maven projects"
    build_midonet_maven -Drpm \
        -Dmido.rpm.release=`version_git_to_rpm_release $pkgver` \
        -Dmido.rpm.version=`version_git_to_rpm_version $pkgver`
    collect_midonet_rpms $destdir

    echo "DONE"
}

do_prepare() {
    if [ -z "$1" ] ; then
         echo "Usage: $0 prepare <VERSION>"
         exit 1
    fi
    v=$1
    debV=`version_git_to_deb $v`
    rpmV=`version_git_to_rpm_version $v`
    rpmR=`version_git_to_rpm_release $v`
    mvnV=`get_pom_version pom.xml`
    echo "Preparing files for release"
    git_setup
    find . -maxdepth 2 -name pom.xml | \
        while read pom ; do
            sed -i -e "s/$mvnV/$v/g" $pom
        done
    echo "DONE"
}


COMMAND="$1"
VERSION="$2"
shift

set -e
case $COMMAND in
    deb) do_package_deb $VERSION ;;
    rhel) do_package_rhel $VERSION ;;
    prepare) do_prepare $@ ;;
    *) usage ;;
esac
