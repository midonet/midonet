#!/bin/bash

source venv/bin/activate
pushd tests/mdts/tests/performance_tests/

SANDBOX_FLAVOUR="default_v2_neutron+kilo+perf"

while getopts ":f:h" opt; do
    case $opt in
    f)
        SANDBOX_FLAVOUR=$OPTARG
        ;;
    h)
        echo "$0 [-f SANDBOX_FLAVOUR] [-o OVERRIDE_DIRECTORY]" \
             " [-p PROVISIONING_SCRIPT]"
        exit 1
        ;;
    esac
done

./run_tests.sh -r $WORKSPACE/tests -l logs

if [ "$SANDBOX_FLAVOUR" = "default_v2_neutron+kilo+perf" ] ; then
    pushd /tmp/jfr
    tar -cvzf $WORKSPACE/perf_jfr.tar.gz *
    popd
fi

popd

