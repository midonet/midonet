#!/bin/bash
#
# This script generates RPM and debian packages.
#
# Usage: ./package.sh deb [VERSION]
# Usage: ./package.sh rpm [VERSION] [RPM_REVISION]
# Usage: ./package.sh clean
#
# Build dependencies:
#   In short, do the following on Ubuntu based distribution to install
#   build dependencies.
#
#   $ sudo apt-get install -y rubygems-integration rpm ruby-ronn ruby-dev && sudo gem install fpm
#
#   Here's list of dependencies:
#
#   * fpm (https://github.com/jordansissel/fpm):
#       This enables us to produce both RPM and debian packages easily
#       withough requiring details about their packaging systems
#
#   * rpm: of course rpmbuild is needed to produce RPM packages
#
#   * ronn: a tool to produce man pages from markdown

set -e

## Common args for rpm and deb
FPM_BASE_ARGS=$(cat <<EOF
--architecture 'noarch' \
--license 'Apache License, Version 2.0' \
--vendor 'MidoNet' \
--maintainer "Midokura" \
--url 'http://midonet.org'
EOF
)

DESCRIPTION='Python client library for MidoNet API'

function clean() {
    find . -name "*.pyc" -exec rm {} \;
    rm -f doc/*.{gz,.1}
    rm -rf build
    rm -f python-midonetclient*.deb
    rm -f python3-midonetclient*.deb
    rm -f python-midonetclient*.rpm
    rm -f python-midonetclient*.tar
}

function build_protobuf_modules() {
    PROTOC=${PROTOC_EXECUTABLE:-protoc}
    mkdir -p src/midonetclient/topology/_protobuf
    $PROTOC -I=../nsdb/src/main/proto/ --python_out=src/midonetclient/topology/_protobuf ../nsdb/src/main/proto/commons.proto
    $PROTOC -I=../nsdb/src/main/proto/ --python_out=src/midonetclient/topology/_protobuf ../nsdb/src/main/proto/topology_api.proto
    $PROTOC -I=../nsdb/src/main/proto/ --python_out=src/midonetclient/topology/_protobuf ../nsdb/src/main/proto/topology.proto
    touch src/midonetclient/topology/_protobuf/__init__.py
}

function build_man_pages() {
    ronn --roff doc/*.ronn 2> /dev/null
    gzip -f doc/*.1
}

function build_hook_scripts() {
    for file in before-remove.sh after-install.sh after-upgrade.sh; do
        sed -e "s/@PACKAGE_NAME@/$1/g" \
            package-hooks/$file.in > package-hooks/$file
    done
}

function package_rpm() {
    RPM_BUILD_DIR=build/rpm/
    mkdir -p  $RPM_BUILD_DIR/usr/lib/python2.6/site-packages/
    mkdir -p  $RPM_BUILD_DIR/usr/lib/python2.7/site-packages/
    mkdir -p  $RPM_BUILD_DIR/usr/bin/
    mkdir -p  $RPM_BUILD_DIR/usr/share/man/man1

    cp -r  src/midonetclient $RPM_BUILD_DIR/usr/lib/python2.6/site-packages/
    cp -r  src/midonetclient $RPM_BUILD_DIR/usr/lib/python2.7/site-packages/
    cp src/bin/midonet-cli $RPM_BUILD_DIR/usr/bin/
    cp doc/*.gz $RPM_BUILD_DIR/usr/share/man/man1/
    RPM_ARGS="$RPM_ARGS -v $version"
    RPM_ARGS="$RPM_ARGS -C build/rpm"
    RPM_ARGS="$RPM_ARGS -d 'python >= 2.6' -d 'python < 2.8'"
    RPM_ARGS="$RPM_ARGS --epoch 2"
    RPM_ARGS="$RPM_ARGS --iteration $rpm_revision"

    build_hook_scripts python-midonetclient
    eval fpm $FPM_BASE_ARGS $RPM_ARGS \
        --name 'python-midonetclient' \
        --url 'http://midonet.org' \
        --description \'Python client library for MidoNet API\' \
        -d 'python-webob' -d 'python-httplib2' \
        --before-remove package-hooks/before-remove.sh \
        --after-install package-hooks/after-install.sh \
        --after-upgrade package-hooks/after-upgrade.sh \
        -s dir -t rpm .
}

function package_deb() {
    DEB_BUILD_DIR=build/deb
    mkdir -p  $DEB_BUILD_DIR/usr/share/man/man1/
    cp doc/*.gz $DEB_BUILD_DIR/usr/share/man/man1/

    DEB_ARGS="$DEB_ARGS -v $version"
    DEB_ARGS="$DEB_ARGS -C build/deb"
    DEB_ARGS="$DEB_ARGS --epoch 2"
    DEB_ARGS="$DEB_ARGS --deb-priority optional"

    _PMC_VERSION_OVERRIDE="$version"
    export _PMC_VERSION_OVERRIDE
    PYTHONDONTWRITEBYTECODE=1
    export PYTHONDONTWRITEBYTECODE

    eval fpm $FPM_BASE_ARGS $DEB_ARGS \
        --name python-midonetclient-doc \
        --description \"$DESCRIPTION - doc\" \
        -s dir -t deb .
    build_hook_scripts python-midonetclient
    eval fpm $FPM_BASE_ARGS $DEB_ARGS \
        --name python-midonetclient \
        --description \"$DESCRIPTION - Python 2.x\" \
        -s python \
        --python-package-name-prefix python \
        --python-bin python2.7 \
        --python-install-bin /usr/bin \
        --python-install-lib /usr/lib/python2.7/dist-packages \
        --before-remove package-hooks/before-remove.sh \
        --after-install package-hooks/after-install.sh \
        --after-upgrade package-hooks/after-upgrade.sh \
        -t deb ./setup.py
    if type python3 > /dev/null; then
        build_hook_scripts python3-midonetclient
        eval fpm $FPM_BASE_ARGS $DEB_ARGS \
            --name python3-midonetclient \
            --description \"$DESCRIPTION - Python 3.x\" \
            -s python \
            --python-package-name-prefix python3 \
            --python-bin python3 \
            --python-install-bin /usr/bin \
            --python-install-lib /usr/lib/python3/dist-packages \
            --before-remove package-hooks/before-remove.sh \
            --after-install package-hooks/after-install.sh \
            --after-upgrade package-hooks/after-upgrade.sh \
            -t deb ./setup.py
    fi
}

function package_tar() {
    TAR_BUILD_DIR=build/tar/
    mkdir -p  $TAR_BUILD_DIR/usr/lib/python2.7/site-packages/
    mkdir -p  $TAR_BUILD_DIR/usr/bin/
    mkdir -p  $TAR_BUILD_DIR/usr/share/man/man1

    cp -r  src/midonetclient $TAR_BUILD_DIR/usr/lib/python2.7/site-packages/
    cp src/bin/midonet-cli $TAR_BUILD_DIR/usr/bin/
    cp doc/*.gz $TAR_BUILD_DIR/usr/share/man/man1/

    PKG_NAME="python-midonetclient-$version"
    TAR_ARGS="$TAR_ARGS -n $PKG_NAME"
    TAR_ARGS="$TAR_ARGS -p $PKG_NAME.tar.gz"
    TAR_ARGS="$TAR_ARGS -C $TAR_BUILD_DIR"

    eval fpm $TAR_ARGS -s dir -t tar .
}

case "$1" in
  deb)
      version=$2
      if [ -z $version ] ; then
          echo "Aborted. invalid options: $*"
          exit 1
      fi
      build_man_pages
      package_deb
      ;;
  rpm)
      version=$2
      rpm_revision=$3
      if [ -z $version ] ; then
          echo "Aborted. invalid options: $*"
          exit 1
      fi
      if [ -z $rpm_revision ] ; then
          echo "Aborted. invalid options: $*"
          exit 1
      fi
      build_protobuf_modules
      build_man_pages
      package_rpm
      ;;
  tar)
      version=$2
      if [ -z $version ] ; then
          echo "Aborted. invalid options: $*"
          exit 1
      fi
      build_protobuf_modules
      build_man_pages
      package_tar
      ;;
  clean)
      clean
      ;;
  *)
      echo "Aborted. invalid options: $*"
      exit 1
      ;;
esac
