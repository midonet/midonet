source venv/bin/activate
pushd tests/mdts/tests/compatibility_tests/

./run_tests.sh -r $WORKSPACE/tests -l logs

popd

