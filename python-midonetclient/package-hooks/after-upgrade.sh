if command -v pyclean >/dev/null 2>&1; then
    pyclean -p python-midonetclient
else
    dpkg -L python-midonetclient | grep \.py$ | while read file
    do
        rm -f "${file}"[co] >/dev/null
    done
fi
if command -v pycompile >/dev/null 2>&1; then
    pycompile -p python-midonetclient
fi
