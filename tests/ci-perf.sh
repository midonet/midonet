pushd tests/mdts/tests/performance_tests/

./run_tests.sh -r $WORKSPACE/tests -l logs
PERF_TEST_RESULT=$?

popd

