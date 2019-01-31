#! /bin/sh

set -e
set -x

OUTPUT_DIR=/root/output

extract_output() {
	IMAGE=$1
	CONTAINER_ID=$(docker container run --read-only -d ${IMAGE})
	docker container cp ${CONTAINER_ID}:${OUTPUT_DIR} .
	docker container rm -f ${CONTAINER_ID}
}

# Build packages
docker build -t midonet-deb --target builder-debian .
extract_output midonet-deb
docker build -t midonet-rpm --target builder-rpm .
extract_output midonet-rpm

# Build tester images
docker build -t midonet-tester-unit --target tester-unit .
docker build -t midonet-tester-integration-netlink --target tester-integration-netlink .
docker build -t midonet-tester-integration-midolman --target tester-integration-midolman .
