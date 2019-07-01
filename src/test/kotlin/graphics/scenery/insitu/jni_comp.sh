export JAVA_HOME="$(/usr/libexec/java_home -v 1.8)"
g++ -c -fPIC -I${JAVA_HOME}/include -I${JAVA_HOME}/include/darwin SharedSpheresExample.cpp -o shmSpheresTrial.o
g++ -dynamiclib -o libshmSpheresTrial.dylib shmSpheresTrial.o -lc
