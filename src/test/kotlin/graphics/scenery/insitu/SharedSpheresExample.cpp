// Save as "SharedSpheresExample.cpp"
#include <jni.h>       // JNI header provided by JDK
#include <iostream>    // C++ standard IO header
#include "SharedSpheresExample.h"  // Generated
#include <sys/ipc.h> 
#include <sys/shm.h>
#include <sys/sem.h>
#include <sys/types.h>
using namespace std;

#define NRANK 2
#define NSEM 2
#define SEMINIT 0
#define SEMOPS 1
#define GET_RANK(rank, toggle) (2*rank+1+(toggle))

int shmid, semid[NRANK], toggle = -1;
float *str = NULL;
union semun sem_attr;
struct sembuf semops[SEMOPS];

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



// Implementation of the native method sayHello()
JNIEXPORT int JNICALL Java_graphics_scenery_insitu_SharedSpheresExample_sayHello(JNIEnv *env, jobject thisObj) {
	cout << "Hello World from C++!" << endl;
   return 1;
}

// given world rank of process, call its two semaphores to find the rank currently used
// if toggle already set, wait until other shm is used (producer only uses one at a time), then attach to it
JNIEXPORT jobject JNICALL Java_graphics_scenery_insitu_SharedSpheresExample_getSimData (JNIEnv *env, jobject thisObj, int worldRank) {

	printf("paramter rec: %d\n", worldRank);

	// ftok to generate unique key
	key_t key;

	// if toggle = -1 initialize and check current rank, else wait for next rank
	if (toggle == -1) {
		std::cout << "initializing semaphores" << std::endl;
		// initialize semaphores
		for (int i = 0; i < NRANK; ++i) {
			key = ftok("/tmp", GET_RANK(worldRank, i));
			semid[i] = semget(key,NSEM,0666|IPC_CREAT); // possibly remove IPC_CREAT here
			if (semid[i] == -1) {
				perror("semget"); exit(1);
			}
		}

		std::cout << "looking for available memory" << std::endl;

		// find current rank used by producer, without blocking (may go wrong if called exactly as producer reallocates, possibly another mutex)
		for (toggle = 0; toggle < NRANK; ++toggle)
			if (semctl(semid[toggle], 1, GETVAL) == 0) // producer using toggle
				break;

		if (toggle >= NRANK) {
			std::cout << "error: no memory" << std::endl;
			for (int i = 0; i < NRANK; ++i)
				semctl(semid[i], 0, IPC_RMID); // remove semaphores
			exit(1);
		}

		std::cout << "found memory " << toggle << std::endl;
	} else {
		std::cout << "waiting for memory " << (1^toggle) << std::endl;
		// wait until producer uses 1^toggle
		semops[0].sem_num = 1;
		semops[0].sem_op  = 0;
		semops[0].sem_flg = 0;
		if (semop(semid[1^toggle], semops, 1) == -1) {
			perror("semop(1,0,0)"); exit(1);
		}
		std::cout << "memory " << (1^toggle) << " available" << std::endl;

		// detach from toggle, change toggle to 1^toggle, attach to new toggle
		if (str != NULL)
			detach();
		toggle ^= 1;
	}

	attach(worldRank);

	std::cout<<"Hello! We are in SimData! Data read from memory:" <<str[0] << std::endl;

	jobject bb = (env)->NewDirectByteBuffer((void*) str, 1000);

	return bb;
}

JNIEXPORT void JNICALL Java_graphics_scenery_insitu_SharedSpheresExample_deleteShm (JNIEnv *env, jobject thisObj) {
	detach();
}