#!/usr/bin/env bash
cwd=$(cd "$(dirname "$0")";pwd)
cd ${cwd}
JARS=$(echo ./libs/*.jar | tr ' ' ':')
JAVA_OPTS="-Dfile.encoding=UTF-8 -Dlog4j.configuration=./conf/log4j.properties -Dlog4j.configurationFile=./conf/log4j2.xml"
java $JAVA_OPTS -cp $JARS com.monkeyboy.data.JenkinsClient

