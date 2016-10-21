if [ -f /opt/midonet-venv/bin/activate ]; then
    . /opt/midonet-venv/bin/activate
elif
    . venv/bin/activate
fi

cd tests/mdts/tests/functional_tests

./run_tests.sh -r $WORKSPACE/tests -l logs -G -x

cd -


