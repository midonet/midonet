#!/bin/bash
# vim: fdm=marker foldmarker={,} ts=2 ss=2 ff=unix tw=72
# ------------------------------------------------------
# Midonet packaging script
# ------------------------------------------------------

#############################################################################

NEW_POM_VERSION=
KEEP_GIT=0
CLEAN=1
WILL_PACKAGE=1
PACKAGE_DEBIAN=0
PACKAGE_RHEL=0
DEST_DIR=`pwd`/
PACKAGES_STORED_DIRS="midolman/target/"

# the package name
PACKAGE_NAME="midonet"

# Space separated, these are expected to contain a package.sh script that takes
# two params: (deb|rhel) version-tag
EXTRA_SUB_PACKAGES="python-midonetclient"

# Maven flag for verbose/quiet
MAVEN_VERBOSE_FLAG=-q

log()   { echo "[PACKAGING] $1" ; }
error() { log "ERROR: $1" ; }
warn()  { log "WARNING: $1" ; }
abort() { log "$1" ; exit 1 ; }
stage() { log "STAGE: $1" ; }

usage() {
    cat <<EOF

Midonet release script
Usage:

  --version <VERSION>   the new version for the packages
  --debian | -d | --deb build Debian packages
  --rhel | -r | --rpm   build RHEL packages
  --dest <DIR>          destination directory for the packages
  --name <NAME>         use this package name
  --skip-git | -N       do not commit/push/... anything to Git
  --skip-clean          do not perform a Git clean before building stuff...

Note that we are assuming the following transitions. Any other combination will
very likely cause an error:

  - snapshot -> rc, e.g.: 1.2.0-SNAPSHOT -> 1.2.0-rc1
  - rc -> final, e.g.: 1.2.4-rc3 -> 1.2.4
  - final -> hotfix, e.g.: 1.2.0 -> 1.2.0.hf5
  
EOF
}

while [ $# -gt 0 ] ; do
    case "$1" in
        --version)
          NEW_POM_VERSION=$2
          shift
          ;;
        --name)
          PACKAGE_NAME=$2
          shift
          ;;
        --dest)
          DEST_DIR=$2
          shift
          ;;
        --debian|-d|--deb)
          WILL_PACKAGE=1
          PACKAGE_DEBIAN=1
          ;;
        --rhel|-r|--rpm)
          WILL_PACKAGE=1
          PACKAGE_RHEL=1
          ;;
        --skip-git|-N)
          KEEP_GIT=1
          ;;
        --skip-clean)
          CLEAN=0
          ;;
        --debug)
          MAVEN_VERBOSE_FLAG=
          ;;  
        --help|-h)
          usage
          exit 0
          ;;
        *)
          log "Unknown argument $1"
          usage
          exit 1
          ;;
    esac
    shift
done

#############################################################################

export -n LC_LANG LANG

current_pom_version() {
    echo $(mvn org.apache.maven.plugins:maven-help-plugin:2.1.1:evaluate \
                 -Dexpression=project.version | \
                 egrep -v '^\[|Downloading:' | \
                 awk 1 ORS='')    
}

log "Obtaining current version from POM"
CURRENT_POM_VERSION=$(current_pom_version)
log "Version: $CURRENT_POM_VERSION"

if [ $WILL_PACKAGE -eq 1 ] ; then
    # Cleans the repo, ensures that the destination dir is both given and exists,
    # and resets the submodules
    log "Preparing for packaging.."
    if [ $CLEAN -eq 1 ] ; then
        # Will wipe off everything in the current dir, resetting state to the
        # current git head.
        REVISION=`git log --pretty=format:'%h %d %s <%an>' -1`
        log "Cleaning to revision: $REVISION -1"
        git clean -dxn && git checkout .
        git clean -dxf
        log "Clone is clean"
    fi
    
    [ "x$DEST_DIR" = "" ] && abort "Destination directory not specified"
    mkdir -p $DEST_DIR || abort "Could not create destination directory"

    log "Fetching git submodules.."
    git submodule --quiet init && git submodule --quiet update    

    #############################
    ## Version management
    #############################
    if [ "x$NEW_POM_VERSION" != "x" ] ; then
        if [ "x$CURRENT_POM_VERSION" != "x$NEW_POM_VERSION" ] ; then
            log "Updating $PACKAGE_NAME POMs from $CURRENT_POM_VERSION to $NEW_POM_VERSION"
            mvn -q versions:set -DnewVersion=$NEW_POM_VERSION
            CURRENT_POM_VERSION=$(current_pom_version)
            log "Version raised to $CURRENT_POM_VERSION..."

            if [ $KEEP_GIT -eq 0 ] ; then
                git commit -asm "Updating client module and POMs for $NEW_POM_VERSION"
                log "The commit is ready to send for review: \
                - verify changes with 'git log -1 HEAD~1' \
                - push for review to the appropriate branch"
            else
                warn "Did not commit the new POMs"
            fi
        else
            log "$PACKAGE_NAME already set to $NEW_POM_VERSION"
        fi
    else
        warn "POMs version not raised! Will use version $CURRENT_POM_VERSION"
    fi

    log "Claning up..."
    mvn $MAVEN_VERBOSE_FLAG -DskipTests -Dmaven.test.skip=true clean

    #############################
    ## Debian packaging
    #############################
    if [ $PACKAGE_DEBIAN -eq 1 ] ; then
        log "Building debian packages, version: $CURRENT_POM_VERSION destination: $DEST_DIR"

        log "Bulding $PACKAGE_NAME (Debian) with Maven"
        mvn -o $MAVEN_VERBOSE_FLAG -DskipTests -Dmaven.test.skip=true package
        log "Debian packages built successfully"
    fi

    #############################
    ## RedHat packaging
    #############################
    if [ $PACKAGE_RHEL -eq 1 ] ; then    
        log "Building RPM packages, version $CURRENT_POM_VERSION, destination $DEST_DIR"
        RPM_VERSION=`echo $CURRENT_POM_VERSION | sed -e 's/-.*//g'` # take the 1.3.0 bit from 1.2.3-*
        RPM_RELEASE=`echo $CURRENT_POM_VERSION | sed -e 's/.*-//g'` # take the bit after - if exists
        if [ "$RPM_VERSION" = "$RPM_RELEASE" ] ; then
            log "Looks like this is a FINAL release for $RPM_VERSION"
            RPM_RELEASE="1.0" # a final
        else
            log "Looks like this is a non final release for $RPM_VERSION, $RPM_RELEASE"
            TIMESTAMP=$(date +%Y%m%d%H%M)
            PRE_VERSION=$(echo $CURRENT_POM_VERSION | sed -e "s/^.*-//g" | sed -e "s/SNAPSHOT/$TIMESTAMP/g")
            RPM_RELEASE="0.1.$PRE_VERSION" # a pre release
        fi

        log "Bulding $PACKAGE_NAME (RHEL) with Maven"
        log "Using version:$RPM_VERSION release:$RPM_RELEASE"
        mvn -o $MAVEN_VERBOSE_FLAG -DskipTests -Dmaven.test.skip=true \
               -Drpm -Dmido.rpm.release="$RPM_RELEASE" \
               -Dmido.rpm.version="$RPM_VERSION" \
               package
        log "RHEL packages built successfully"
    fi

    log "Collecting packages"
    for PKG in `find $PACKAGES_STORED_DIRS -name '*.rpm'` `find $PACKAGES_STORED_DIRS -name '*.deb'` ; do
        log "... copying $PKG"
        mv -f $PKG $DEST_DIR/
    done
    
    #############################
    ## Push tags & co
    #############################
    if [ $KEEP_GIT -eq 0 ] ; then
        log "Pushing local changes to repo..."
        git tag -a "v$NEW_POM_VERSION" -m "Version $NEW_POM_VERSION"
        git push --tags
    fi

fi

log "Done!"
exit 0





