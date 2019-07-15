/*
 * Shared memory consumer, storing and updating pointer to shared memory
 *
 *
 */

 #ifndef SHM_BUFFER_HPP
 #define SHM_BUFFER_HPP

 #include <future>

 #include "SemManager.hpp"

 // TODO for synchronization, store both current and old pointers, support both attach after detach and attach before detach,
 //

 class ShmBuffer {

	SemManager sems;
	size_t size; // buffer size in bytes

	// bool used[NKEYS];   // whether each key is currently used (allocated and not yet deleted, incl. not released by consumer)

	int current_key;    // takes values 0 or 1; most recent memory read from keys[current_key]
	int shmid;          // the shared memory id used for current key (-1 if not used)
	void *ptr;          // pointer to current shared memory

	std::future<void> out;

	void loop(); // event loop // this should be implemented in Kotlin

	void attach(); // attach to current memory
	void detach(); // detach from current memory

	void find_active(); // find key used by producer and set current_key to it; if no key found set it to -1

public:

	ShmBuffer(std::string pname, int rank, size_t size);
	~ShmBuffer();

	void reattach(bool wait); // wait to attach to next memory, or simply check synchronously without blocking

	void init(); // initiate event loop
	void term(); // terminate event loop

 };

 #endif