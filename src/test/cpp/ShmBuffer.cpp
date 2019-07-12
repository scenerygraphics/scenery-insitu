#include <iostream>

#include "ShmBuffer.hpp"

#define CONSEM 0   // index of semaphore for consumer
#define PROSEM 1   // index of semaphore for producer
#define KEYINIT -1 // initial value of current_key to signify no previous memory allocated
#define NEXTKEY (1^current_key)

ShmBuffer::ShmBuffer(std::string pname, int rank. size_t size) : sems(pname, rank), size(size), current_key(KEYINIT), shmid(-1), ptr(NULL)
{
	find_active();
}

ShmBuffer::~ShmBuffer()
{
	if (current_key != KEYINIT)
		detach();
}

void ShmBuffer::find_active()
{
	current_key = KEYINIT;
	for (int i = 0; i < NKEYS; ++i) {
		if (sems.get(i, PROSEM) > 0) { // producer using memory i
			current_key = i;
			break;
		}
	}
}

void ShmBuffer::attach()
{
	if (ptr != NULL)
		return;

	int key = sems[current_key];
    
	std::cout << "attaching to key " << key " with no " << current_key << std::endl; // test

	// shmget returns an identifier in shmid
	shmid = shmget(key, size, 0666|IPC_CREAT);
	if (shmid == -1) {
		perror("shmget"); exit(1);
	}
	std::cout << "shmid: " << shmid << std::endl; // test

	// shmat to attach to shared memory
	ptr = shmat(shmid, NULL, 0);
	if (ptr == NULL) {
		perror("shmat"); exit(1);
	}

	// increment consumer semaphore
	sems.incr(current_key, CONSEM);
}

void ShmBuffer::detach()
{
	if (ptr == NULL)
		return;

	// detach from shared memory
	shmdt(ptr);
	ptr = NULL;

	// release semaphore, alerting producer to delete shmid
	sems.decr(current_key, CONSEM);
}

void ShmBuffer::loop()
{
	do {
		reattach(true);
		// do something with ptr
	} while (ptr != NULL); // TODO make sure to stop looping when another call to detach is made
}



void ShmBuffer::reattach(bool wait) // should keep some sort of mutex for ptr, so that it is never read as null between detaching and attaching
{
	if (current_key == KEYINIT) { // called initially
		std::cout << "looking for available memory" << std::endl; // test
		find_active();
		if (current_key == KEYINIT)
			return; // possibly wait here
		std::cout << "found memory " << current_key << std::endl; // test
	} else {
		if (wait) {
			std::cout << "waiting for memory " << NEXTKEY << std::endl; // test
			sems.waitgeq(NEXTKEY, PROSEM, 1);
		} else {
			std::cout << "checking for memory " << NEXTKEY << std::endl; // test
			if (sems.get(NEXTKEY, PROSEM) == 0)
				return;
		}
		std::cout << "memory " << NEXTKEY << " available" << std::endl; // test

		// detach from current memory, toggle key
		detach();
		current_key = NEXTKEY;
	}
	// assert ptr == NULL

	// attach to new key
	attach();
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