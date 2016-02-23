FROM sandbox/midolman:base
MAINTAINER MidoNet (http://midonet.org)

# Install Java.
RUN apt-get update && apt-get install -y --no-install-recommends openjdk-7-jre
