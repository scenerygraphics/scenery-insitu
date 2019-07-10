/*
 * Shared memory allocator class
 *
 * Provides two public functions like malloc and free
 *
 * shm_alloc(size): given size in bytes, allocate shared memory of given size, return pointer
 * shm_free(ptr): given pointer, remove shared memory; do nothing if ptr is NULL
 */

#ifndef SHM_ALLOC_HPP_
#define SHM_ALLOC_HPP_

#include <stdlib.h>

#define NKEYS 2 // number of keys used per world rank

#define NSEM 2 // number of semaphores per key; 0th semaphore for no of consumers, 1st semaphore for no of producers not using shm
#define SEMOPS 10 // max number of semops to perform at a time

class ShmAllocator {

	const char *pname;  // path name for ftok
	int rank;           // world rank

	int keys[NKEYS];    // keys to be used and toggled
	bool used[NKEYS];   // whether each key is currently used
	int shmids[NKEYS];  // the shared memory id used for each key (-1 if not used)
	int semids[NKEYS];  // the semaphore id used for each key
	void *ptrs[NKEYS];  // pointers allocated for each key (NULL if not allocated)

	int current_key;    // takes values 0 or 1; allocate new memory using keys[current_key]

	// for semaphore calls
	union semun sem_attr;
    struct sembuf semops[SEMOPS];

    void wait_del(int key); // wait to delete ptrs[key]

public:
	ShmAllocator(const char *pname, int rank);
	~ShmAllocator();

	void *shm_alloc(size_t size); // allocate shared memory of given size
	void shm_free(void *ptr); // free shared memory segment associated to pointer
};

#endif