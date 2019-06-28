// Save as "SharedSpheresExample.cpp"
#include <jni.h>       // JNI header provided by JDK
#include <iostream>    // C++ standard IO header
#include "SharedSpheresExample.h"  // Generated
#include <sys/ipc.h> 
#include <sys/shm.h> 
using namespace std;

int shmid;
float *str;
float *buf = NULL;


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

    std::cout << "shmid: " << shmid << std::endl;
  
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
    shmctl(shmid,IPC_RMID,NULL); 

    return;
}