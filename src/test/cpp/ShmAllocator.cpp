/*
 * Shared memory allocator class
 *
 *
 *
 */

#include <iostream>
#include <future>

#include <sys/ipc.h>
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

#define NSEM 2     // number of semaphores per key; 0th semaphore for no of consumers, 1st semaphore for no of producers not using shm
#define CONSEM 0   // index of semaphore for consumer
#define PROSEM 1   // index of semaphore for producer
#define SEMOPS 10  // max number of semops to perform at a time
#define KEYINIT -1 // initial value of current_key to signify no previous memory allocated

#define PROJ_ID(rank, toggle) (2*(rank)+1+(toggle)) // generate proj_id to send to ftok

ShmAllocator::ShmAllocator(std::string pname, int rank) : pname(pname), rank(rank), current_key(KEYINIT)
{
	for (int i = 0; i < NKEYS; ++i) {
		printf("rank:%d\ttoggle:%d\tid:%d\t", rank, i, PROJ_ID(rank, i)); // test
		keys[i] = ftok(pname.data(), PROJ_ID(rank, i));
		printf("key:%d\n", keys[i]); // test

		shmids[i] = -1;
		ptrs[i] = NULL;
		used[i] = false;

		// initialize semaphore
		semids[i] = semget(keys[i], NSEM, 0666|IPC_CREAT);

		// currently, no consumers or producers
		sem_attr.val = 0;
		semctl(semids[i], CONSEM, SETVAL, sem_attr); // no consumers -> 0
		sem_attr.val = 1;
		semctl(semids[i], PROSEM, SETVAL, sem_attr); // no producers -> 1
	}
}

ShmAllocator::~ShmAllocator()
{
	for (int i = 0; i < NKEYS; ++i) {
		// delete pointer if it is used
		shm_free(ptrs[i]); // to avoid deadlocks, for now may just call shmctl instead of having to wait for consumer

		// delete semaphore
		semctl(semids[i], 0, IPC_RMID);
	}
}

void *ShmAllocator::shm_alloc(size_t size)
{
	// if current key is used, toggle it
	current_key &= 1; // -1 becomes 1
	// if (used[current_key]) // does not change key if allocating after freeing
		current_key ^= 1;
	printf("rank:%d\tkey:%d\n", current_key, keys[current_key]);

	// assert !used[current_key]; user must not be able to allocate more than twice
	// wait for current key to stop being used by consumer
	// technically used is also like a semaphore

	// allocate memory with new key
	shmids[current_key] = shmget(keys[current_key], size, 0666|IPC_CREAT);
	printf("shmid:%d\n", shmids[current_key]);
	ptrs[current_key] = shmat(shmids[current_key], NULL, 0);
	printf("ptr:%ld\n", (long) ptrs[current_key]);

	// mark new key as used
	used[current_key] = true;

	// decrement semaphore for new key to signal consumer
	semops[0].sem_num = PROSEM;
    semops[0].sem_op  = -1;
   	semops[0].sem_flg = 0;
    if (semop(semids[current_key], semops, 1) == -1) {
    	perror("semop"); exit(1);
    }
	printf("decremented semaphore %d of rank %d\n", PROSEM, current_key);

    // return pointer
    return ptrs[current_key];
}

void ShmAllocator::shm_free(void *ptr)
{
	static std::future<void> out;

	// return if null pointer
	if (ptr == NULL)
		return;

	// find the key that ptr refers to
	int key;
	for (key = 0; key < NKEYS; ++key)
		if (ptrs[key] == ptr) // here, actually check if ptr lies in the interval allocated for ptrs[key], possibly storing sizes
			break;
	// if no key found, return
	if (key == NKEYS)
		return;

	// assert used[key]

	// mark key as unused
	// used[key] = false;
	// shmdt(ptr); // better here than after waiting; pointer should be unusable after free is called

	// increment semaphore for key
	semops[0].sem_num = PROSEM;
    semops[0].sem_op  = 1;
    semops[0].sem_flg = 0;
    if (semop(semids[key], semops, 1) == -1) {
    	perror("semop"); exit(1);
    }
	printf("incremented semaphore %d of rank %d\n", PROSEM, key);

    // wait_del(key);
    out = std::async(std::launch::async, &ShmAllocator::wait_del, this, key);
}

void ShmAllocator::wait_del(int key)
{
	// wait for consumer to stop using key
	printf("waiting for semaphore %d of rank %d\n", CONSEM, key);
	semops[0].sem_num = CONSEM;
    semops[0].sem_op  = 0;
    semops[0].sem_flg = 0;
    if (semop(semids[key], semops, 1) == -1) {
    	perror("semop"); exit(1);
    }
	printf("waited for semaphore %d of rank %d\n", CONSEM, key);

	// mark key as unused by consumer
	// execute after waiting so that another allocate call to the key would not mess things up
	used[key] = false; // ensure that ptrs[key] and shmids[key] only change when used[key] is false

    // deallocate shared memory
    shmdt(ptrs[key]); // ptrs[key] must not have changed
    shmctl(shmids[key], IPC_RMID, NULL); // shmid[key] must not have changed

    ptrs[key] = NULL;
    shmids[key] = -1;
}