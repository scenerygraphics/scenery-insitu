// Save as "TestConsumer.cpp"
#include <jni.h>       // JNI header provided by JDK
#include <iostream>    // C++ standard IO header
#include "TestConsumer.h"  // Generated
#include <sys/ipc.h> 
#include <sys/shm.h>
#include <sys/types.h>
#include <unistd.h>
#include <fcntl.h>
using namespace std;

#define DTYPE float

#define RANK 12

int shmid;
DTYPE *str;

struct timespec start;

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

JNIEXPORT void JNICALL Java_graphics_scenery_insitu_benchmark_TestConsumer_sysvDelete (JNIEnv *env, jobject thisObj) {
    shmdt(str);
}