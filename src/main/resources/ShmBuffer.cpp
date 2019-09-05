#include <iostream>
#include <sys/ipc.h>
#include <sys/shm.h>
#include <cstdlib>

#include "ShmBuffer.hpp"

#define CONSEM 0   // index of semaphore for consumer
#define PROSEM 1   // index of semaphore for producer
#define KEYINIT -1 // initial value of current_key to signify no previous memory allocated
#define NEXTKEY (1^current_key)
#define PREVKEY NEXTKEY

ShmBuffer::ShmBuffer(std::string pname, int rank, size_t size, bool verbose) : sems(pname, rank, verbose, false), size(size), current_key(KEYINIT), shmid(-1), verbose(verbose) // , ptr(NULL)
{
	for (int i = 0; i < NKEYS; ++i)
		ptrs[i] = NULL;
	// find_active();
}

ShmBuffer::~ShmBuffer()
{
	if (current_key != KEYINIT) {
		detach(true);
		detach(false);
	}
}

void ShmBuffer::find_active() // move to attach(), should always be called before it
{
	current_key = KEYINIT;
	for (int i = 0; i < NKEYS; ++i) {
		if (sems.get(i, PROSEM) > 0) { // producer using memory i
			current_key = i;
			break;
		}
	}
}

void *ShmBuffer::attach()
{
	if (ptrs[current_key] != NULL)
		return ptrs[current_key];

	int key = sems[current_key];
    
	if (verbose) std::cout << "attaching to key " << key << " with no " << current_key << std::endl; // test

	// shmget returns an identifier in shmid
	shmid = shmget(key, size, 0666|IPC_CREAT);
	if (shmid == -1) {
		perror("shmget"); std::exit(1);
	}
	if (verbose) std::cout << "shmid: " << shmid << std::endl; // test

	// shmat to attach to shared memory
	ptrs[current_key] = shmat(shmid, NULL, 0);
	if (ptrs[current_key] == NULL) {
		perror("shmat"); std::exit(1);
	}

	// increment consumer semaphore
	if (sems.get(current_key, CONSEM) == 0) // using semaphore as mutex
	    sems.incr(current_key, CONSEM);

	return ptrs[current_key];
}

void ShmBuffer::detach(bool current)
{
	int key = current ? current_key : PREVKEY;

	if (ptrs[key] == NULL)
		return;

	// detach from shared memory
	shmdt(ptrs[key]);
	ptrs[key] = NULL;

	// release semaphore, alerting producer to delete shmid
	sems.decr(key, CONSEM);
}

void ShmBuffer::update_key(bool wait) // should keep some sort of mutex for ptr, so that it is never read as null between detaching and attaching
{
	if (current_key == KEYINIT) { // called initially
		std::cout << "looking for available memory" << std::endl; // test

		do {
			find_active();
		} while (current_key == KEYINIT); // loop until an active memory segment is found

		if (verbose) std::cout << "found memory " << current_key << std::endl; // test
	} else {
		if (wait) {
			if (verbose) std::cout << "waiting for memory " << NEXTKEY << std::endl; // test
			sems.waitgeq(NEXTKEY, PROSEM, 1);
		} else {
			if (verbose) std::cout << "checking for memory " << NEXTKEY << std::endl; // test
			while (sems.get(NEXTKEY, PROSEM) == 0);
		}
		if (verbose) std::cout << "memory " << NEXTKEY << " available" << std::endl; // test

		// detach from current memory, toggle key
		// detach();
		current_key = NEXTKEY;
	}
	// assert ptr == NULL

	// attach to new key
	// attach();
}

// ignore these for now

/*

void ShmBuffer::loop()
{
	do {
		reattach(true);
		// do something with ptr
	} while (ptr != NULL); // TODO make sure to stop looping when another call to detach is made
}

void ShmBuffer::init()
{
	if (current_key == KEYINIT)
		find_active();
	out = std::async(std::launch::async, &ShmBuffer::loop, this);
}

void ShmBuffer::term()
{
	detach();
	out.wait(); // TODO use wait_for instead
}

*/