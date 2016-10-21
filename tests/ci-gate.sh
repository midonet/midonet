if [ -f /opt/midonet-sandbox-venv/bin/activate ]; then
    . /opt/midonet-sandbox-venv/bin/activate
else
    . venv/bin/activate
fi

cd tests/mdts/tests/functional_tests

./run_tests.sh -r $WORKSPACE/tests -l logs -G -x

cd -


