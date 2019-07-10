/*
 * Shared memory allocator class
 *
 *
 *
 */

#include <iostream>
#include <sys/ipc.h>
#include <sys/sem.h>
#include <sys/shm.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdlib.h>
#include <stdio.h>
#include <errno.h>

#include "ShmAllocator.hpp"

#define NSEM 2 // number of semaphores per key; 0th semaphore for no of consumers, 1st semaphore for no of producers not using shm
#define CONSEM 0 // index of semaphore for consumer
#define PROSEM 1 // index of semaphore for producer
#define SEMOPS 10 // max number of semops to perform at a time

#define PROJ_ID(rank, toggle) (2*(rank)+1+(toggle)) // generate proj_id to send to ftok

ShmAllocator::ShmAllocator(const char *pname, int rank) : pname(pname), rank(rank), current_key(0)
{
	for (int i = 0; i < NKEYS; ++i) {
		keys[i] = ftok(pname, PROJ_ID(rank, i));
		shmids[i] = -1;
		ptrs[i] = NULL;
		used[i] = false;

		// initialize semaphore
		semids[i] = semget(keys[i], NSEM, 0666|IPC_CREAT);

		// currently, no consumers or producers
		sem_attr.val = 0;
		semctl(semid[i], CONSEM, SETVAL, sem_attr); // no consumers -> 0
		sem_attr.val = 1;
		semctl(semid[i], PROSEM, SETVAL, sem_attr); // no producers -> 1
	}
}

ShmAllocator::~ShmAllocator()
{
	for (int i = 0; i < NKEYS; ++i) {
		// delete pointer if it is used
		shm_free(ptrs[i]);

		// delete semaphore
		semctl(semid[i], 0, IPC_RMID);
	}
}

void *ShmAllocator::shm_alloc(size_t size)
{
	// if current key is used, toggle it
	if (used[current_key])
		current_key ^= 1;

	// assert !used[current_key]; user must not be able to allocate more than twice
	// wait for current key to stop being used by consumer

	// allocate memory with new key
	shmids[current_key] = shmget(keys[current_key], size, 0666|IPC_CREAT);
	ptrs[current_key] = shmat(shmids[current_key], NULL, 0);

	// mark new key as used
	used[current_key] = true;

	// decrement semaphore for new key to signal consumer
	semops[0].sem_num = PROSEM;
    semops[0].sem_op  = -1;
   	semops[0].sem_flg = 0;
    if (semop(semid[current_key], semops, 1) == -1) {
    	perror("semop"); exit(1);
    }

    // return pointer
    return ptrs[current_key];
}

void ShmAllocator::shm_free(void *ptr)
{
	// return if null pointer
	if (ptr == NULL)
		return;

	// find the key that ptr refers to
	int i;
	for (i = 0; i < NKEYS; ++i)
		if (ptrs[i] == ptr)
			break;
	// if no key found, return
	if (i == NKEYS)
		return;

	// assert used[i]

	// mark key as unused
	// used[i] = false;
	// shmdt(ptr); // better here than after waiting; pointer should be unusable after free is called

	// increment semaphore for key
	semops[0].sem_num = PROSEM;
    semops[0].sem_op  = 1;
    semops[0].sem_flg = 0;
    if (semop(semid[i], semops, 1) == -1) {
    	perror("semop"); exit(1);
    }

    // TODO execute these asynchronously

    // wait for consumer to stop using key
	semops[0].sem_num = CONSEM;
    semops[0].sem_op  = 0;
    semops[0].sem_flg = 0;
    if (semop(semid[i], semops, 1) == -1) {
    	perror("semop"); exit(1);
    }

	// mark key as unused by consumer
	// execute after waiting so that another allocate call to the key would not mess things up
	used[i] = false; // ensure that ptrs[i] and shmids[i] only change when used[i] is false

    // deallocate shared memory
    shmat(ptr);
    shmctl(shmids[i], IPC_RMID, NULL); // shmid[i] must not have changed

    ptrs[i] = NULL;
    shmids[i] = -1;
}