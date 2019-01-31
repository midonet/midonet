ARG BUILD_WORKDIR=/src/github.com/midonet/midonet
ARG OUTPUT_DIR=/root/output

FROM ubuntu:16.04 as builder-deps
LABEL maintainer "YAMAMOTO Takashi <yamamoto@midokura.com>"
ARG BUILD_WORKDIR
WORKDIR ${BUILD_WORKDIR}

RUN DEBIAN_FRONTEND=noninteractive apt-get update
RUN DEBIAN_FRONTEND=noninteractive apt-get -y --no-install-recommends --no-install-suggests install \
	openjdk-8-jdk-headless \
	gcc \
	g++ \
	make \
	git
FROM builder-deps as builder-src
LABEL maintainer "YAMAMOTO Takashi <yamamoto@midokura.com>"
COPY . .

FROM builder-src as builder-build
LABEL maintainer "YAMAMOTO Takashi <yamamoto@midokura.com>"
RUN mkdir /root/.gradle
RUN echo org.gradle.daemon=false >> /root/.gradle/gradle.properties
RUN ./gradlew shadowJar

FROM builder-build as builder-pkg-base
LABEL maintainer "YAMAMOTO Takashi <yamamoto@midokura.com>"
RUN DEBIAN_FRONTEND=noninteractive apt-get -y --no-install-recommends --no-install-suggests install \
	ruby-dev \
	ruby-ronn
RUN gem install rake fpm

FROM builder-pkg-base as builder-debian
LABEL maintainer "YAMAMOTO Takashi <yamamoto@midokura.com>"
ARG OUTPUT_DIR
RUN DEBIAN_FRONTEND=noninteractive apt-get -y --no-install-recommends --no-install-suggests install \
	python3-setuptools \
	python-setuptools
RUN ./gradlew debian
RUN mkdir ${OUTPUT_DIR}
RUN find . -name "*.deb" -exec cp {} ${OUTPUT_DIR} \;

FROM builder-pkg-base as builder-rpm
LABEL maintainer "YAMAMOTO Takashi <yamamoto@midokura.com>"
ARG OUTPUT_DIR
RUN DEBIAN_FRONTEND=noninteractive apt-get -y --no-install-recommends --no-install-suggests install rpm
RUN ./gradlew rpm
RUN mkdir ${OUTPUT_DIR}
RUN find . -name "*.rpm" -exec cp {} ${OUTPUT_DIR} \;

FROM builder-build as builder-tests
LABEL maintainer "YAMAMOTO Takashi <yamamoto@midokura.com>"
RUN ./gradlew testClasses integrationJar

FROM builder-tests as tester-unit
LABEL maintainer "YAMAMOTO Takashi <yamamoto@midokura.com>"
# REVISIT(yamamoto): RollingOutputStreamTest uses logrotate (MI-2192)
RUN DEBIAN_FRONTEND=noninteractive apt-get -y --no-install-recommends --no-install-suggests install logrotate
CMD ./gradlew test

FROM builder-tests as tester-integratiton-base
LABEL maintainer "YAMAMOTO Takashi <yamamoto@midokura.com>"
# NOTE(yamamoto): netlink integration tests need "ip" "sudo"
# NOTE(yamamoto): midolman integration tests need "quagga"
# https://docs.midonet.org/docs/latest-en/quick-start-guide/ubuntu-1604_newton/content/_repository_configuration.html
RUN echo deb http://debian.datastax.com/community 2.2 main > /etc/apt/sources.list.d/datastax.list
RUN DEBIAN_FRONTEND=noninteractive apt-get -y --no-install-recommends --no-install-suggests install curl
RUN curl -L https://debian.datastax.com/debian/repo_key | apt-key add -
# NOTE(yamamoto): midonet-nightly provides "vpp"
# NOTE(yamamoto): midonet misc provides "python-support" (required by "dsc22") and "quagga"
RUN echo deb http://builds.midonet.org/midonet-nightly unstable main > /etc/apt/sources.list.d/midonet.list
RUN echo deb http://builds.midonet.org/misc stable main >> /etc/apt/sources.list.d/midonet.list
RUN curl -L https://builds.midonet.org/midorepo.key | apt-key add -
RUN apt-get update
RUN DEBIAN_FRONTEND=noninteractive apt-get -y --no-install-recommends --no-install-suggests install iproute2 sudo quagga dsc22 vpp iputils-ping

FROM tester-integratiton-base as tester-integration-netlink
LABEL maintainer "YAMAMOTO Takashi <yamamoto@midokura.com>"
# NOTE(yamamoto): This needs extra priviledges. Use "docker run --privileged"
CMD cd netlink && java -jar ./build/libs/integrationTests.jar

FROM tester-integratiton-base as tester-integration-midolman
LABEL maintainer "YAMAMOTO Takashi <yamamoto@midokura.com>"
RUN sed -ibak 's/\(native_transport_port: \).*$/\19142/' /etc/cassandra/cassandra.yaml
RUN mkdir -p /usr/share/midolman
RUN cp midolman/src/deb/init/vpp-start /usr/share/midolman
RUN mkdir -p /etc/midolman
RUN mkdir -p /var/log/midolman
# NOTE(yamamoto): This needs extra priviledges. Use "docker run --privileged"
CMD /etc/init.d/cassandra start && cd midolman && java -Djava.library.path=./build/nativelibs/ -jar ./build/libs/integrationTests.jar
