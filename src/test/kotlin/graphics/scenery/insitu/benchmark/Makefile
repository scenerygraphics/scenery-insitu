CPP_DIR := ../../../../../../main/resources
JAVA_HOME := $(shell /usr/libexec/java_home -v 1.8)

all: cpp jni

cpp:
	g++ -c -I$(CPP_DIR) $(CPP_DIR)/SemManager.cpp -o SemManager.o

jni: SemManager.o
	g++ -c -fPIC -I$(JAVA_HOME)/include -I$(JAVA_HOME)/include/darwin -I${CPP_DIR} TestConsumer.cpp -o testConsumer.o
	g++ -dynamiclib -o libtestConsumer.dylib testConsumer.o SemManager.o -lc

clean:
	rm SemManager.o testConsumer.o libtestConsumer.dylib