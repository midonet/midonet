FROM sandbox/midolman:base
MAINTAINER MidoNet (http://midonet.org)

# Install Java.
RUN apt-get update && \
    apt-get install -qy --no-install-recommends openjdk-7-jre

RUN apt-get install -qy --force-yes midolman=0:1.9.6
