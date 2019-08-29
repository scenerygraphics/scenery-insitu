// Save as "TestConsumer.cpp"
#include <jni.h>       // JNI header provided by JDK
#include <iostream>    // C++ standard IO header
#include "TestConsumer.h"  // Generated

#include <unistd.h>
#include <fcntl.h>

#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <inttypes.h>
#include <sys/ipc.h>
#include <sys/shm.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <netdb.h>
#include <netinet/in.h>
#include <arpa/inet.h>

#include "SemManager.hpp"

using namespace std;

#define VERBOSE false

#define DTYPE float

#define RANK 11

#define ISPROD false
#define PROSEM 1
#define CONSEM 0
#define OWNSEM (ISPROD ? PROSEM : CONSEM)
#define OPPSEM (ISPROD ? CONSEM : PROSEM)

// wait for opposite semaphore to be decremented
#define WAIT() do { 		\
	sem.incr(0, OPPSEM);	\
	if (VERBOSE) std::cout << "waiting for " << OPPSEM << std::endl; \
	sem.wait(0, OPPSEM);	\
	if (VERBOSE) std::cout << "waited for " << OPPSEM << std::endl; \
} while (0)

// decrement own semaphore
#define SIGNAL() do { 		\
	if (VERBOSE) std::cout << "decrementing " << OWNSEM << std::endl; \
	sem.decr(0, OWNSEM);	\
	if (VERBOSE) std::cout << "decremented " << OWNSEM << std::endl; \
} while (0)

int shmid;
DTYPE *str;

struct timespec start;

SemManager sem("/tmp", RANK, VERBOSE, ISPROD);

JNIEXPORT void JNICALL Java_graphics_scenery_insitu_benchmark_TestConsumer_semWait(JNIEnv *env, jobject thisObj) {
    WAIT();
}

JNIEXPORT void JNICALL Java_graphics_scenery_insitu_benchmark_TestConsumer_semSignal(JNIEnv *env, jobject thisObj) {
    SIGNAL();
}

JNIEXPORT jobject JNICALL Java_graphics_scenery_insitu_benchmark_TestConsumer_sysvInit(JNIEnv *env, jobject thisObj, jint size) {
	int x = (int) size;

	printf("creating shared memory with size %d\n", x);

	key_t key = ftok("/tmp", RANK);
	shmid = shmget(key, x, 0666|IPC_CREAT);
	if (shmid < 0) { perror("shmget"); exit(1); }

	// acquire pointer
	str = (DTYPE *) shmat(shmid, NULL, 0);
	if (str == MAP_FAILED) { perror("shmat"); exit(1); }

	printf("created shared memory with size %d\n", x);

	jobject bb = (env)->NewDirectByteBuffer((void*) str, x);

	return bb;
}

JNIEXPORT void JNICALL Java_graphics_scenery_insitu_benchmark_TestConsumer_sysvTerm(JNIEnv *env, jobject thisObj) {
    shmdt(str);
}

/*

JNIEXPORT jobject JNICALL Java_graphics_scenery_insitu_benchmark_TestConsumer_sysvReceive (JNIEnv *env, jobject thisObj) {

	if (clock_gettime( CLOCK_REALTIME, &start) < 0) {
		perror("clock_gettime"); exit(1);
	}
	long starttime = start.tv_sec * 1000000L + start.tv_nsec / 1000;

    printf("paramter rec: %d", worldRank);
     // ftok to generate unique key 
    key_t key = ftok("/tmp",worldRank);
  
    // shmget returns an identifier in shmid 
    shmid = shmget(key,2024,0666|IPC_CREAT); 
  
    // shmat to attach to shared memory 
    str = (DTYPE *) shmat(shmid,NULL,0);

    std::cout<<"Hello! We are in SimData! Data read from memory:" <<str[0]; 

    jobject bb = (env)->NewDirectByteBuffer((void*) str, 10000);

    // measure duration to switch from jni call, store current time here
	if (clock_gettime( CLOCK_REALTIME, &start) < 0) {
		perror("clock_gettime"); exit(1);
	}
	str[1] = start.tv_sec * 1000000L + start.tv_nsec / 1000 - starttime;

    return bb;
}

*/