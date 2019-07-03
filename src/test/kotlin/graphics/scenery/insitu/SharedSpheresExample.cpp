// Save as "SharedSpheresExample.cpp"
#include <jni.h>       // JNI header provided by JDK
#include <iostream>    // C++ standard IO header
#include "SharedSpheresExample.h"  // Generated
#include <sys/ipc.h> 
#include <sys/shm.h>
#include <sys/sem.h>
#include <sys/types.h>
using namespace std;

#define NSEM 1

int shmid, semid;
float *str;
float *buf = NULL;
union semun sem_attr; // for semaphore

// Implementation of the native method sayHello()
JNIEXPORT int JNICALL Java_graphics_scenery_insitu_SharedSpheresExample_sayHello(JNIEnv *env, jobject thisObj) {
    cout << "Hello World from C++!" << endl;
   return 1;
}

JNIEXPORT jobject JNICALL Java_graphics_scenery_insitu_SharedSpheresExample_getSimData (JNIEnv *env, jobject thisObj, int worldRank) {

    printf("paramter rec: %d\n", worldRank);
     // ftok to generate unique key 
    key_t key = ftok("/tmp",worldRank);

    std::cout << "key: " << key << std::endl;
  
    // shmget returns an identifier in shmid 
    shmid = shmget(key,2024,0666|IPC_CREAT);
    if (shmid == -1) {
        perror("shmget"); exit(1);
    }

    std::cout << "shmid: " << shmid << std::endl;

    if (buf != NULL) {
        semid = semget(key,NSEM,0666|IPC_CREAT); // TODO decide on nsems
        if (semid == -1) {
            perror("semget"); exit(1);
        }
        std::cout << "semid: " << semid << std::endl;
        // initialize semaphore to show memory consumed
        sem_attr.val = 0; // locked
        if (semctl(semid, 0, SETVAL, sem_attr) == -1) {
            perror("semctl"); exit(1);
        }
        std::cout << "semaphore " << semid << " set to 0" << std::endl;
    }

    // shmat to attach to shared memory
    float *str1;
    if (buf == NULL)
        str1 = buf = (float*) shmat(shmid, NULL,0);
    else
        str1 = str = (float*) shmat(shmid, NULL,0);

    std::cout<<"Hello! We are in SimData! Data read from memory:" <<str1[0] << std::endl;

    jobject bb = (env)->NewDirectByteBuffer((void*) str1, 1000);

    return bb;
}

JNIEXPORT void JNICALL Java_graphics_scenery_insitu_SharedSpheresExample_deleteShm (JNIEnv *env, jobject thisObj) {
        //detach from shared memory  
    shmdt(str); 
    
    // destroy the shared memory 
    // shmctl(shmid,IPC_RMID,NULL);

    // release semaphore, alerting producer to delete shmid
    sem_attr.val = 1;
    if (semctl(semid, 0, SETVAL, sem_attr) == -1) {
        perror("semctl"); exit(1);
    }

    std::cout << "semaphore " << semid << " set to 1" << std::endl;

    return;
}