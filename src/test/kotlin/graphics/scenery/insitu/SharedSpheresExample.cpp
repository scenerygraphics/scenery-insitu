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

/*

// attach to new shm, assuming toggle is set
void attach(int worldRank)
{
	int rank = GET_RANK(worldRank, toggle);

	std::cout << "attaching to rank " << rank << std::endl;

	key_t key = ftok("/tmp", rank);

	std::cout << "key: " << key << std::endl;

	// shmget returns an identifier in shmid
	shmid = shmget(key,2024,0666|IPC_CREAT);
	if (shmid == -1) {
		perror("shmget"); exit(1);
	}

	std::cout << "shmid: " << shmid << std::endl;

	// shmat to attach to shared memory
	str = (float*) shmat(shmid, NULL,0);

	// increment semaphore
	semops[0].sem_num = 0;
	semops[0].sem_op  = 1;
	semops[0].sem_flg = 0;
	if (semop(semid[toggle], semops, 1) == -1) {
		perror("semop(0,1,0)"); exit(1);
	}
	std::cout << "incremented semaphore 0 of rank " << toggle << std::endl;
}

// detach from current shm, assuming str is set
// later call this if program is interrupted, to keep semaphores consistent
void detach()
{
	//detach from shared memory
	shmdt(str);

	// destroy the shared memory
	// shmctl(shmid,IPC_RMID,NULL);

	// release semaphore, alerting producer to delete shmid
	semops[0].sem_num = 0;
	semops[0].sem_op  = -1;
	semops[0].sem_flg = 0;
	if (semop(semid[toggle], semops, 1) == -1) {
		perror("semop(0,-1,0)"); exit(1);
	}

	std::cout << "decremented semaphore 0 of rank " << toggle << std::endl;

	return;
}

*/


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