#!/bin/bash

project_name="midonet"
git_submodules="python-midonetclient"
build_packages="python-midonetclient"
maven_projects="."

usage() {
    echo "Usage: $(basename $0) deb|rhel
" >&2
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

check_git_submodule_version() {
    submod=$1
    ver=$2

    echo "Checking git tag for $submod"

    pushd $submod > /dev/null

    gitmodver=`git describe`
    if [ "$gitmodver" != "$ver" ] ; then
        echo "Tag mismatch in $submod: found=[$gitmodver] expected=[$ver]"
        fail_if_final_release $ver
    fi
    popd > /dev/null
}

check_rpm_pkg_version() {
    submod=$1
    gver=$2
    spec="$submod/rhel/$submod.spec"
    ver=`version_git_to_rpm_version $gver`
    rel=`version_git_to_rpm_release $gver`
    echo "Checking RPM package versions for tag=[$gver], should be [$ver]"
    rpmpkgver=`cat $spec | grep ^Version | sed -e 's/^Version:\s*//'`
    rpmpkgrel=`cat $spec | grep ^Release | sed -e 's/^Release:\s*//'`
    if [ "$rpmpkgver" != "$ver" ] ; then
        echo "RPM version mismatch in $submod: found=[$rpmpkgver] expected=[$ver]"
        exit 1
    fi
    if [ "$rpmpkgrel" != "$rel" ] ; then
        echo "RPM release mismatch in $submod: found=[$rpmpkgrel] expected=[$rel]"
        fail_if_final_release $gver
    fi
}

check_deb_pkg_version() {
    submod=$1
    gver=$2
    ver=`version_git_to_deb $gver`
    chl="$submod/debian/changelog"
    echo "Checking debian package versions for tag=[$gver], should be [$ver]"
    debpkgver=`dpkg-parsechangelog -l$chl -c1 | grep ^Version | cut -d ' ' -f 2`
    if [ "$debpkgver" != "$ver" ] ; then
        echo "Debian version mismatch in $submod: found=[$debpkgver] expected=[$ver]"
        fail_if_final_release $gver
    fi
}

build_rpm() {
    submod=$1
    destdir=$2
    spec="$submod/rhel/$submod.spec"
    ver=`cat $spec | grep ^Version | sed -e 's/^Version:\s*//'`
    pushd $submod > /dev/null
    git archive HEAD --prefix=$submod-$ver/ -o ~/rpmbuild/SOURCES/$submod-$ver.tar
    gzip -f ~/rpmbuild/SOURCES/$submod-$ver.tar
    rpmbuild --quiet -ba rhel/$submod.spec
    popd > /dev/null
    find ~/rpmbuild/RPMS/ | grep ${submod}-$ver | grep rpm$ | while read pkg ; do
        cp $pkg $destdir
    done
}

build_deb() {
    submod=$1
    destdir=$2

    chl="$submod/debian/changelog"
    debpkgver=`dpkg-parsechangelog -l$chl -c1 | grep ^Version | cut -d ' ' -f 2`
    pushd $submod > /dev/null
    dpkg-buildpackage -rfakeroot -b -us -uc
    popd > /dev/null
    mv *_${debpkgver}_*.deb $destdir
    mv *_${debpkgver}_*.changes $destdir
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

check_git_tags() {
    pkgver=$1
    echo "Checking tag version numbers in git submodules"
    for submod in $git_submodules ; do
        check_git_submodule_version $submod $pkgver
    done
}

do_package_deb() {
    echo "Building for debian"
    git_setup

    echo "Reading version number from git"
    pkgver=`git describe`

    echo "Package version: $pkgver"
    check_git_tags $pkgver

    # drop the leading 'v' used in the git tags
    pkgver=`echo $pkgver | sed -e s/^v//`

    echo "Checking version numbers in debian packages"
    for debpkg in $build_packages ; do
        check_deb_pkg_version $debpkg $pkgver
    done
    echo "Checking version numbers in maven project"
    check_mvn_version . $pkgver

    destdir="$project_name-$pkgver"
    echo "Packages will be placed in $destdir"
    mkdir $destdir

    echo "Building debian packages"
    for debpkg in $build_packages ; do
        build_deb $debpkg $destdir
    done

    echo "Building maven projects"
    build_midonet_maven
    collect_midonet_debs $destdir

    echo "DONE"
}

do_package_rhel() {
    echo "Building for RHEL"
    git_setup

    echo "Reading version number from git"
    pkgver=`git describe`

    echo "Package version: $pkgver"
    check_git_tags $pkgver

    # drop the leading 'v' used in the git tags
    pkgver=`echo $pkgver | sed -e s/^v//`

    echo "Checking version numbers in debian packages"
    for pkg in $build_packages ; do
        check_rpm_pkg_version $pkg $pkgver
    done
    echo "Checking version numbers in maven project"
    check_mvn_version . $pkgver

    destdir="$project_name-$pkgver"
    echo "Packages will be placed in $destdir"
    mkdir $destdir

    echo "Building rpm packages"
    for pkg in $build_packages ; do
        build_rpm $pkg $destdir
    done

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
    git submodule init
    git submodule update
    for pkg in $build_packages ; do
        dch -v "$debV" -c $pkg/debian/changelog "$debV"
        sed -i -e "s/^Version: .*$/Version:    $rpmV/" $pkg/rhel/$pkg.spec
        sed -i -e "s/^Release: .*$/Release:    $rpmR/" $pkg/rhel/$pkg.spec
    done
    find . -maxdepth 2 -name pom.xml | \
        while read pom ; do 
            sed -i -e "s/$mvnV/$v/g" $pom
        done
    echo "DONE"
}


COMMAND="$1"
shift

set -e
case $COMMAND in
    deb) do_package_deb ;;
    rhel) do_package_rhel ;;
    prepare) do_prepare $@ ;;
    *) usage ;;
esac
