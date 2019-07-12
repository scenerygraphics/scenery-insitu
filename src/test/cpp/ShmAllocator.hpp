/*
 * Shared memory allocator class
 *
 * Provides two public functions like malloc and free
 *
 * shm_alloc(size): given size in bytes, allocate shared memory of given size, return pointer
 * - toggle current key
 * - allocate shared memory with new key
 * - mark new key as used, both in used[] and in the semaphore
 *
 * shm_free(ptr): given pointer, remove shared memory; do nothing if ptr is NULL
 * - find key associated to pointer
 * - increment semaphore
 * - asynchronously (calling wait_del):
 *   - wait for consumer to release key
 *   - mark key as unused
 *   - delete shared memory
 */

#ifndef SHM_ALLOC_HPP_
#define SHM_ALLOC_HPP_

#include <string>
#include <sys/sem.h>

#include "SemManager.hpp"

#define SHMINIT -1 // value of shmids[key] when no shared memory is associated to key

class ShmAllocator {

	SemManager sems;

	bool used[NKEYS];   // whether each key is currently used (allocated and not yet deleted, incl. not released by consumer)
	int shmids[NKEYS];  // the shared memory id used for each key (-1 if not used)
	void *ptrs[NKEYS];  // pointers allocated for each key (NULL if not allocated)

	int current_key;    // takes values 0 or 1; most recent memory allocated using keys[current_key]

    void wait_del(int key); // wait to delete ptrs[key], called from shm_free

public:
	ShmAllocator(std::string pname, int rank); // generate two keys per rank, pass pname to ftok, initialize semaphores
	~ShmAllocator(); // delete semaphores and any remaining memory segments

	void *shm_alloc(size_t size); // allocate shared memory of given size
	void shm_free(void *ptr); // free shared memory segment associated to pointer, which may be NULL
};

#endif