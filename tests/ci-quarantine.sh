source venv/bin/activate
pushd tests/mdts/tests/functional_tests

./run_tests.sh -r $WORKSPACE/tests -l logs -X

popd

