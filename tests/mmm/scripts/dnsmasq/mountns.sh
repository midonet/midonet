#! /bin/sh

n=$1; shift

if test -d /run; then
    mount --bind /run.$n /run
else
    mount --bind /var/run.$n /var/run
fi
mount --bind /var/log/quagga.$n /var/log/quagga
mount --bind /etc/quagga.$n /etc/quagga

mount --bind /var/lib/midolman.$n /var/lib/midolman
mount --bind /var/log/midolman.$n /var/log/midolman
mount --bind /etc/midolman.$n /etc/midolman

# generates host uuid
cat <<EOF > /etc/midolman/host_uuid.properties
# generated for MMM MM $n
host_uuid=00000000-0000-0000-0000-00000000000$n
EOF

exec $*
