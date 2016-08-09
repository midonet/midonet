if command -v pyclean >/dev/null 2>&1; then
    pyclean -p python-midonetclient
else
    PYFILES=""
    if [ -f /etc/debian_version ]; then
        PYFILES=$(dpkg -L python-midonetclient | grep \.py$)
    elif [ -f /etc/redhat-release ]; then
        PYFILES=$(rpm -ql python-midonetclient | grep \.py$)
    fi
    for file in $PYFILES; do
        rm -f "${file}"[co] >/dev/null
    done
fi
