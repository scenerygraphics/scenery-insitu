// Save as "SharedSpheresExample.cpp"
#include <jni.h>       // JNI header provided by JDK
#include <iostream>    // C++ standard IO header
#include "SharedSpheresExample.h"  // Generated
#include <sys/ipc.h> 
#include <sys/shm.h>
#include <sys/sem.h>
#include <sys/types.h>
using namespace std;

#include "ShmBuffer.hpp"

#define PNAME "/tmp"
#define SIZE 2024

ShmBuffer *buf = NULL; // TODO later add functions to delete
float *str = NULL;
int myRank = -1;

// Implementation of the native method sayHello()
JNIEXPORT int JNICALL Java_graphics_scenery_insitu_SharedSpheresExample_sayHello(JNIEnv *env, jobject thisObj) {
	cout << "Hello World from C++!" << endl;
   return 1;
}

// given world rank of process, call its two semaphores to find the rank currently used
// if toggle already set, wait until other shm is used (producer only uses one at a time), then attach to it
JNIEXPORT jobject JNICALL Java_graphics_scenery_insitu_SharedSpheresExample_getSimData (JNIEnv *env, jobject thisObj, int worldRank) {

	printf("paramter rec: %d\n", worldRank);

	if (myRank != worldRank) {
		myRank = worldRank;
		if (buf != NULL)
			delete buf;
		buf = new ShmBuffer(PNAME, myRank, SIZE);
	}

	buf->update_key();
	str = (float *) buf->attach();

	std::cout<<"Hello! We are in SimData! Data read from memory:" <<str[0] << std::endl;

	jobject bb = (env)->NewDirectByteBuffer((void*) str, 1000);

	return bb;
}

JNIEXPORT void JNICALL Java_graphics_scenery_insitu_SharedSpheresExample_deleteShm (JNIEnv *env, jobject thisObj) {
	buf->detach(false); // detach from old
}