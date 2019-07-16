export JAVA_HOME="$(/usr/libexec/java_home -v 1.8)"
export CPP_HOME=../../../../cpp
g++ -c -fPIC -I${JAVA_HOME}/include -I${JAVA_HOME}/include/darwin -I${CPP_HOME} SharedSpheresExample.cpp -o shmSpheresTrial.o
g++ -dynamiclib -o libshmSpheresTrial.dylib shmSpheresTrial.o ${CPP_HOME}/*.o -lc
