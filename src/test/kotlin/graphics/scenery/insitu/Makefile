CPP_DIR := ../../../../../main/resources
JAVA_HOME := $(shell /usr/libexec/java_home -v 1.8)

all: cpp jni

cpp:
	g++ -c -I$(CPP_DIR) $(CPP_DIR)/SemManager.cpp -o SemManager.o
	g++ -c -I$(CPP_DIR) $(CPP_DIR)/ShmBuffer.cpp -o ShmBuffer.o

jni: SemManager.o ShmBuffer.o
	g++ -c -fPIC -I${JAVA_HOME}/include -I${JAVA_HOME}/include/darwin -I${CPP_DIR} SharedSpheresExample.cpp -o shmSpheresTrial.o
	g++ -dynamiclib -o libshmSpheresTrial.dylib shmSpheresTrial.o ShmBuffer.o SemManager.o -lc

clean:
	rm SemManager.o ShmBuffer.o shmSpheresTrial.o libshmSpheresTrial.dylib