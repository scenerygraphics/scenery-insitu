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

#define PNAME(isProp) ((isProp) ? "/home" : "/")

#define DTYPE double
#define GRIDLEN 11
#define NUMPARS (GRIDLEN*GRIDLEN*GRIDLEN)
#define SIZE(i) (i ? 6*NUMPARS*sizeof(DTYPE) : 3*NUMPARS*sizeof(DTYPE))
#define VERBOSE true

ShmBuffer *buf[] = {NULL, NULL};
DTYPE *str[] = {NULL, NULL};
int myRank = -1;

// Implementation of the native method sayHello()
JNIEXPORT int JNICALL Java_graphics_scenery_insitu_SharedSpheresExample_sayHello(JNIEnv *env, jobject thisObj) {
	if (VERBOSE)
		cout << "Hello World from C++!" << endl;
	return 1;
}

// given world rank of process, call its two semaphores to find the rank currently used
// if toggle already set, wait until other shm is used (producer only uses one at a time), then attach to it
JNIEXPORT jobject JNICALL Java_graphics_scenery_insitu_SharedSpheresExample_getSimData (JNIEnv *env, jobject thisObj, jboolean isProp, int worldRank) {

	if (VERBOSE)
		printf("paramter rec: %d\t%d\n", (int) isProp, worldRank);

	int i = (int) isProp;

	if (buf[i] == NULL || myRank != worldRank) {
		myRank = worldRank;
		if (buf[i] != NULL)
			delete buf[i];
		buf[i] = new ShmBuffer(PNAME(i), myRank, SIZE(i), VERBOSE); // TODO make size changeable later
	}

	buf[i]->update_key(true);
	str[i] = (DTYPE *) buf[i]->attach();

	if (VERBOSE)
		std::cout<<"Hello! We are in SimData! Data read from memory:" << str[i][0] << std::endl;

	jobject bb = (env)->NewDirectByteBuffer((void*) str[i], SIZE(i));

	return bb;
}

JNIEXPORT void JNICALL Java_graphics_scenery_insitu_SharedSpheresExample_deleteShm (JNIEnv *env, jobject thisObj, jboolean isProp) {
	int i = (int) isProp;

	if (buf[i] != NULL)
		buf[i]->detach(false); // detach from old
}

JNIEXPORT void JNICALL Java_graphics_scenery_insitu_SharedSpheresExample_terminate (JNIEnv *env, jobject thisObj) {
	for (int i = 0; i < 2; ++i) {
		if (buf[i] != NULL) {
			buf[i]->detach(true); // detach from current
			delete buf[i];
			buf[i] = NULL;
		}
	}
}