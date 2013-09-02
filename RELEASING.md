Releasing Midonet
=================

This document explains the steps to be followed in order to produce a
new release of Midonet, including the agent, api and python client
packages.

Ideally, this document should eventually disintegrate in favour of a
fully automated system.

## 1. Tagging

The first step is to ensure that the midonet project and all relevant
submodules (so far only python-midonetclient) are tagged with the
correct version tag. In this document, $VERSION will refer to
'1.2.0-rc1', $TAG will refer to 'v1.2.0-rc1'.

- Checkout the relevant revision. Use git submodule init and git
  submodule update to ensure that the python-midonetclient submodule
  contents are downloaded to the right rev.
- Update version numbers. The ./release.sh script can give you a hand
  with this. From the repo's basedir execute:

    ./release.sh prepare $VERSION

  this will update all the pom.xml files with the right tag.
- After checking the diff, commit the changes both in the submodule and
  the midonet repo.
- Push the changes to the python-midonetclient repository. But NOT the
  midonet one.
- Move into python-midonetclient, create the new tag for the new
  version.

    git tag -a $TAG

  and push it to the remote.
- From the midonet repo's basedir run

    git add python_midonetclient
    git add .j
    git commit

  this will update the midonet repo's reference to the client's
  submodule. And of course include all the various pom files updated by
  the release script. Now you can submit the changes (that will include
  the version changes in the pom files plus the update in the
  python-midonetclient submodule) to Gerrit. Remember not to push these
  changes directly to the repo, or Gerrit will get out of sync.

## 2. Building

Assuming your changes have gone through the review cycle and are now
merged in the main repo, fetch and checkout the tag, and ensure that the
contents of the python-midonetclient are also present.

To generate the Debian packages:

    ./release.sh deb

The results should appear in a new folder midonet-$VERSION-deb/ and
should look like this (e.g.: for $VERSION='1.2.0-rc1').

    midolman_1.2.0~20130902.113458.868.deb
    python-midonetclient_1.2.0~rc1_all.deb
    midonet-api_1.2.0~20130902.113529.433.deb

To generate RHEL packages:

    # pretend we're in a rhel environment
    schroot -c centos6
    # generate packages
    ./release.sh rhel

the results should appear in a new folder 'midonet-$VERSION-rhel/' and
look like this:

    midolman-1.2.0-0.1.rc1.noarch.rpm
    midonet-api-1.2.0-0.1.rc1.noarch.rpm
    python-midonetclient-1.2.0-0.1.rc1.noarch.rpm

## 3. Repository updates

### APT

Log in to apt.midokura.com, the three debian packages generated above
should be available somewhere in the machine. We'll use the paths for
v1.2, but this may change for your specific version.

    cd /var/packages/v1.2/test
    reprepro -C main includedeb precise $PATH_TO_MIDOLMAN_DEB
    reprepro -C non-free includedeb precise $PATH_TO_MIDONET_API_DEB
    reprepro -C non-free includedeb precise $PATH_TO_PYTHON_CLIENT_DEB

If you want to verify that the packages are installed, try:

    reprepro list precise

They should appear in the list, check the timestamps.

### RHEL

Again, log in to yum.midokura.com and run:

    # sign the files:
    rpm --addsign $PATH_TO_MIDOLMAN_RPM
    rpm --addsign $PATH_TO_MIDONET_API_RPM
    rpm --addsign $PATH_TO_PYTHON_CLIENT_RPM

    # copy them to the yum repo location
    sudo cp $PATH_TO_MIDOLMAN_RPM /var/www/html/repo/RHEL/6/noarch/
    sudo cp $PATH_TO_MIDONET_API_RPM /var/www/html/repo/RHEL/6/noarch/
    sudo cp $PATH_TO_PYTHON_CLIENT_RPM /var/www/html/repo/RHEL/6/noarch/

    # update repo
    sudo /root/create-yum-repo.sh

And you're set.

## 4. TODO

- Automate most of the steps detailed here
- Move script to Graddle
