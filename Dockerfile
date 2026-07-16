FROM jetty:jdk17

COPY build/libs/search-java-server-1.0-SNAPSHOT.war /var/lib/jetty/webapps/ROOT.war

RUN java -jar "$JETTY_HOME/start.jar" --add-modules=ee10-deploy,ee10-webapp


