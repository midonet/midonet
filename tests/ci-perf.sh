#!/bin/bash

source venv/bin/activate

pushd tests/mdts/tests/performance_tests/

./run_tests.sh -r $WORKSPACE/tests -l logs

popd

pushd /tmp/jfr

tar -cvzf $WORKSPACE/perf_jfr.tar.gz *

popd
