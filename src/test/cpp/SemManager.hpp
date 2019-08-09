/*
 * Semaphore manager for both producer and consumer
 *
 *
 */

#ifndef SEM_MANAGER_HPP
#define SEM_MANAGER_HPP

#include <string>
#include <sys/sem.h>

#define NKEYS   2 // number of keys per each rank
#define NSEMS   2 // number of semaphores per key (one for consumer, one for producer)
#define SEMOPS 10 // max number of consecutive semaphore calls supported

class SemManager {
	std::string pname;
	int rank;
	bool ismain; // whether to manage and delete semaphores, true by default
	bool verbose;

	int keys[NKEYS];    // keys to be used and toggled
	int semids[NKEYS];  // the semaphore id used for each key

	// for semaphore calls
	union semun sem_attr;
    struct sembuf semops[SEMOPS];

public:

	SemManager(std::string pname, int rank, bool verbose = true, bool ismain = true);
	~SemManager();

	const int &operator[](int keyNo); // return key[keyNo]

	void set(int keyNo, int semNo, int value); // directly set semaphore value
	int  get(int keyNo, int semNo); // directly get semaphore value

	void incr(int keyNo, int semNo); // increment semaphore value
	void decr(int keyNo, int semNo); // decrement semaphore value, wait if semaphore equal to 0

	void wait(int keyNo, int semNo, int value = 0); // wait until semaphore equal to value (blocking)
	void waitgeq(int keyNo, int semNo, int value); // wait until semaphore at least value

};

#endif