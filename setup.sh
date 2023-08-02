#!/bin/bash

ARTIFACTORY_HOME=/var/opt/jfrog/artifactory
PROTO=http
USERNAME=alex
PASSWORD=Password1
HOSTNAME=localhost
PORT=8082
API_URL=${PROTO}://${USERNAME}:${PASSWORD}@${HOSTNAME}:${PORT}/artifactory/api


cp rotationPlugin.* ${ARTIFACTORY_HOME}/etc/artifactory/plugins
chown artifactory:artifactory rotationPlugin.*
curl -XPOST ${API_URL}/plugins/reload
curl ${API_URL}/plugins
