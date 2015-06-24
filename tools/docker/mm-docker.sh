#!/bin/bash
#
# Copyright 2015 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

check_command_availability() {
    if !(("$1" "${2:---version}") > /dev/null 2>&1); then
        echo >&2 "$COMMAND: $1 is required to run this script"
        exit 1
    fi
}

mm_dpctl () {
    mm-dpctl --timeout=${TIMEOUT:-60} "$@"
}

# Refer to the following page to know how docker creates network namespaces:
#   https://docs.docker.com/articles/networking/#how-docker-networks-a-container
create_netns_symblink() {
    mkdir -p /var/run/netns
    if [ ! -e /var/run/netns/"$PID" ]; then
        ln -s /proc/"$PID"/ns/net /var/run/netns/"$PID"
        trap 'delete_netns_symlink' 0
        for signal in 1 2 3 13 14 15; do
            trap "delete_netns_symlink; trap - $signal; kill -$signal $$" $signal
        done
    fi
}

delete_netns_symlink() {
    rm -f /var/run/netns/"$PID"
}

add_if() {
    if [ "$#" -lt 2 ]; then
        echo >&2 "Too few arguments for add-if."
        help
        exit 1
    fi

    if [ "$#" -eq 2 -o "$#" -eq 3 ]; then
        DATAPATH="midonet"
    fi
    if [ "$#" -gt 3 ]; then
        DATAPATH="$1"
        shift
    fi

    INTERFACE="$1"
    CONTAINER="$2"
    ADDRESS="$3"
    DEFAULT_GATEWAY="$4"

    if !((mm_dpctl --list-dps | grep "$DATAPATH" > /dev/nul 2>&1) ||
            (mm_dpctl --add-dp "$DATAPATH")); then
        echo >&2 "Failed to create datapathe $DATAPATHE"
        exit 1
    fi

    PID=`docker inspect -f '{{.State.Pid}}' "$CONTAINER"`
    if [ "$PID" = "" ]; then
        echo >&2 "Failed to get the PID of the container $CONTAINER"
        exit 1
    fi

    create_netns_symblink

    # Create a veth pair.
    ID=`uuidgen | sed 's/-//g'`
    IFNAME="${CONTAINER:0:8}-${INTERFACE:0:5}"
    sudo ip link add "${IFNAME}" type veth peer name "${IFNAME}_c"

    # Add one end of veth to the datapath.
    if !((mm_dpctl --add-if "${IFNAME}" "$DATAPATH") > /dev/null 2>&1); then
        echo >&2 "Failed to add \"${IFNAME}\" interface to the datapath $DATAPATH"
        ip link delete "${IFNAME}"
        delete_netns_symlink
        exit 1
    fi

    ip link set "${IFNAME}" up

    # Move "{IFNAME}_c" inside the container and changes its name.
    ip link set "${IFNAME}_c" netns "$PID"
    ip netns exec "$PID" ip link set dev "${IFNAME}_c" name "$INTERFACE"
    ip netns exec "$PID" ip link set "$INTERFACE" up

    if [ -n "$ADDRESS" ]; then
        ip netns exec "$PID" ip addr add "$ADDRESS" dev "$INTERFACE"
    fi

    if [ -n "$DEFAULT_GATEWAY" ]; then
        ip netns exec "$PID" ip route add default via "$DEFAULT_GATEWAY" dev "$INTERFACE"
    fi

    delete_netns_symlink
}

del_if() {
    if [ "$#" -lt 2 ]; then
        help
        exit 1
    fi

    if [ "$#" -eq 2 ]; then
        DATAPATH="midonet"
    fi
    if [ "$#" -eq 3 ]; then
        DATAPATH="$1"
        shift
    fi

    INTERFACE="$1"
    CONTAINER="$2"

    IFNAME="${CONTAINER:0:8}${INTERFACE:0:5}"
    PORT=`mm_dpctl --show-dp midonet \
            | grep -P "Port \#\d+ \"$IFNAME\"" \
            | awk -F '"' '{print $2}'`
    if [ "$PORT" = "" ]; then
        echo >&2 "Failed to find any attached port in $DATAPATH" \
            "for CONTAINER=$CONTAINER and INTERFACE=$INTERFACE"
        exit 1
    fi

    mm_dpctl --delete-if "$PORT" "$DATAPATH"

    ip link delete "$PORT"
}

del_ifs() {
    if [ "$#" -lt 1 ]; then
        help
        exit 1
    fi

    if [ "$#" -eq 1 ]; then
        DATAPATH="midonet"
    fi
    if [ "$#" -eq 2 ]; then
        DATAPATH="$1"
        shift
    fi

    CONTAINER="$1"

    PORTS=`mm_dpctl --show-dp midonet | grep -P "Port \#\d+" | awk -F '"' '{print $2}'`
    if [ -z "$PORTS" ]; then
        exit 0
    fi

    for PORT in $PORTS; do
        mm_dpctl --delete-if "$PORT" "$DATAPATH"
        ip link delete "$PORT"
    done
}

help() {
    cat <<EOF
${COMMAND}: Integrates the network interfaces into MidoNet.
usage: ${COMMAND} COMMAND

Commands:
  add-if [DATAPATH] INTERFACE CONTAINER [IPv4_ADDRESS/NET_MASK] [DEFAULT_GATEWAY]
          Adds INTERFACE inside CONTAINER and connects it as a port in the given
          DATAPATH or "midonet" datapath if it's not provided. It also adds the
          IPv4 address to the interface if it's given with "ip addr add"
          command. If DEFAULT_GATEWAY is given, it sets the default gateway with
          the given IP address and [DATAPATH] MUST BE given along with it in the
          first argument for this subcommand.

          Examples:
            ${COMMAND} add-if dp0 veth8a72ecc1-5a 5506de2b643b 192.168.100.1
            ${COMMAND} add-if veth8a72ecc1-5a 5506de2b643b 192.168.100.1/32
            ${COMMAND} add-if veth8a72ecc1-5a 5506de2b643b
            ${COMMAND} add-if dp0 veth8a72ecc1-5a 5506de2b643b 192.168.1.42/24 192.168.1.1

  del-if [DATAPATH] INTERFACE CONTAINER
          Deletes INTERFACE inside CONTAINER and removes its connection to the
          associated DATAPATH or "midonet" datapath if it's not provided.

          Examples:
            ${COMMAND} del-if dp0 veth8a72ecc1-5a 5506de2b643b
            ${COMMAND} del-if veth8a72ecc1-5a 5506de2b643b

  del-ifs [DATAPATH] CONTAINER
          Removes all MidoNet interfaces associated with the DATAPATH or
          "midonet" datapath if it's not provided from CONTAINER SHA1 value.
          ${COMMAND} del-ifs dp0 5506de2b643b

          Examples:
            ${COMMAND} del-ifs dp0 5506de2b643b
            ${COMMAND} del-ifs 5506de2b643b

Options:
  -h, --help   display this help message.
EOF
}

COMMAND=$(basename $0)
check_command_availability mm-dpctl --list-dps
check_command_availability ip netns
check_command_availability docker
check_command_availability uuidgen

if [ "$#" -eq 0 ]; then
    help
    exit 0
fi

case $1 in
    "add-if")
        shift
        add_if "$@"
        exit 0
        ;;
    "del-if")
        shift
        del_if "$@"
        exit 0
        ;;
    "del-ifs")
        shift
        del_ifs "$@"
        exit 0
        ;;
    -h | --help)
        shift
        help
        exit 0
        ;;
    *)
        echo >&2 "$0: unknown command \"$1\" (use --help for help)"
        exit 1
        ;;
esac
