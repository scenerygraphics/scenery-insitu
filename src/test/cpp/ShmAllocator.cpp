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
#include <cstdlib>

#include "ShmAllocator.hpp"

#define CONSEM 0   // index of semaphore for consumer
#define PROSEM 1   // index of semaphore for producer
#define KEYINIT -1 // initial value of current_key to signify no previous memory allocated

#define PROJ_ID(rank, toggle) (2*(rank)+1+(toggle)) // generate proj_id to send to ftok

#define TESTPRINT if (verbose) printf

ShmAllocator::ShmAllocator(std::string pname, int rank, bool verbose) : sems(pname, rank, verbose, true), current_key(KEYINIT), verbose(verbose)
{
	for (int i = 0; i < NKEYS; ++i) {
		shmids[i] = -1;
		ptrs[i] = NULL;
		// used[i] = false;

		// currently, no consumers or producers
		sems.set(i, CONSEM, 0); // no consumers -> 0
		sems.set(i, PROSEM, 0); // no producers -> 0
	}
}

ShmAllocator::~ShmAllocator()
{
	for (int i = 0; i < NKEYS; ++i) {
		// delete pointer if it is used
		sems.set(i, CONSEM, 0); // don't wait for consumer to finish
		TESTPRINT("set semaphore %d\n", i);
		shm_free(ptrs[i]); // TODO this call seems to freeze sometimes
		TESTPRINT("freed pointer %d\n", i);
		// or call out.wait_for() for a given timeout duration, making out a class field and not a static variable for shm_free
	}
	TESTPRINT("deleted ShmAllocator\n");
}

void *ShmAllocator::shm_alloc(size_t size)
{
	// if current key is used, toggle it
	current_key &= 1; // -1 becomes 1
	// if (used[current_key]) // does not change key if allocating after freeing
		current_key ^= 1;
	TESTPRINT("rank:%d\tkey:%d\n", current_key, sems[current_key]); // test

	// assert !used[current_key]; user must not be able to allocate more than twice
	// wait for current key to stop being used by consumer
	// technically used is also like a semaphore
	used[current_key].lock(); // wait for it to be unused, then mark again
	// TODO change this later to use heap memory and change used back to bool

	// allocate memory with new key
	if ((shmids[current_key] = shmget(sems[current_key], size, 0666|IPC_CREAT)) == -1) {
		perror("shmget"); std::exit(1);
	}
	TESTPRINT("shmid:%d\n", shmids[current_key]); // test
	if ((long) (ptrs[current_key] = shmat(shmids[current_key], NULL, 0)) == -1) {
		perror("shmat"); std::exit(1);
	}
	TESTPRINT("ptr:%ld\n", (long) ptrs[current_key]); // test

	// mark new key as used
	// used[current_key] = true;

	// increment semaphore for new key to signal consumer
	sems.incr(current_key, PROSEM);

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
		return; // TODO change this to free()

	// assert used[key]

	// mark key as unused
	// used[key] = false;
	// shmdt(ptr); // better here than after waiting; pointer should be unusable after free is called

	// decrement semaphore for key
	sems.decr(key, PROSEM);

    // wait_del(key);
    out = std::async(std::launch::async, &ShmAllocator::wait_del, this, key);
}

void ShmAllocator::wait_del(int key)
{
	// wait for consumer to stop using key
	sems.wait(key, CONSEM, 0); // need to check if this is busy waiting

	// execute after waiting so that another allocate call to the key would not mess things up
	// used[key] = false; // ensure that ptrs[key] and shmids[key] only change when used[key] is false

	// deallocate shared memory
    shmdt(ptrs[key]); // ptrs[key] must not have changed
    shmctl(shmids[key], IPC_RMID, NULL); // shmid[key] must not have changed

    ptrs[key] = NULL;
    shmids[key] = -1;

    // mark key as unused by consumer
    used[key].unlock();
}