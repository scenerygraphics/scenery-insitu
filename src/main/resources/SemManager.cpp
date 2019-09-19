/*
 * Semaphore manager for both producer and consumer
 *
 *
 */

#include <cstdlib>

#include "SemManager.hpp"

#define PROJ_ID(rank, toggle) (2*(rank)+1+(toggle)) // generate proj_id to send to ftok (later should be more complex)

#define TESTPRINT if (verbose) printf

SemManager::SemManager(std::string pname, int rank, bool verbose, bool ismain) : pname(pname), rank(rank), verbose(verbose), ismain(ismain)
{
	for (int i = 0; i < NKEYS; ++i) {
		TESTPRINT("rank:%d\ttoggle:%d\tid:%d\t", rank, i, PROJ_ID(rank, i)); // test
		keys[i] = ftok(pname.data(), PROJ_ID(rank, i));
		TESTPRINT("key:%d\n", keys[i]); // test

		// initialize semaphore
		semids[i] = semget(keys[i], NSEMS, 0666|IPC_CREAT);
	}
}

SemManager::~SemManager()
{
	if (ismain) {
		for (int i = 0; i < NKEYS; ++i) {
			// delete semaphore
			TESTPRINT("deleting semaphore %d\n", i);
			semctl(semids[i], 0, IPC_RMID);
			TESTPRINT("deleted semaphore %d\n", i);
		}
	}
	TESTPRINT("deleted SemManager\n");
}

const int &SemManager::operator[](int keyNo)
{
	return keys[keyNo];
}

void SemManager::set(int keyNo, int semNo, int value)
{
	sem_attr.val = value;
    semctl(semids[keyNo], semNo, SETVAL, sem_attr);
}

int SemManager::get(int keyNo, int semNo)
{
	return semctl(semids[keyNo], semNo, GETVAL);
}

void SemManager::incr(int keyNo, int semNo)
{
	semops[0].sem_num = semNo;
    semops[0].sem_op  = 1;
   	semops[0].sem_flg = 0;
    if (semop(semids[keyNo], semops, 1) == -1) {
    	perror("semop"); std::exit(1);
    }
	TESTPRINT("incremented semaphore %d of key %d\n", semNo, keyNo); // test
}

void SemManager::decr(int keyNo, int semNo)
{
	semops[0].sem_num = semNo;
    semops[0].sem_op  = -1;
   	semops[0].sem_flg = 0;
    if (semop(semids[keyNo], semops, 1) == -1) {
    	perror("semop"); std::exit(1);
    }
	TESTPRINT("decremented semaphore %d of key %d\n", semNo, keyNo); // test
}

void SemManager::wait(int keyNo, int semNo, int value)
{
	TESTPRINT("waiting for semaphore %d of key %d to reach %d\n", semNo, keyNo, value); // test
	if (value == 0) {
		semops[0].sem_num = semNo;
		semops[0].sem_op  = 0;
		semops[0].sem_flg = 0; // TODO possibly pass SEM_UNDO here in consumer
		if (semop(semids[keyNo], semops, 1) == -1) {
			perror("semop"); std::exit(1);
		}
	} else {
		// decrement by value, wait for zero (necessary if semaphore was initially higher than value, which in our case doesn't happen), then increment by value
		semops[0].sem_num = semNo;
		semops[0].sem_op  = -value; //value = 1
		semops[0].sem_flg = 0;
		semops[1].sem_num = semNo;
		semops[1].sem_op  = 0;
		semops[1].sem_flg = 0;
		semops[2].sem_num = semNo;
		semops[2].sem_op  = value;
		semops[2].sem_flg = 0;
		if (semop(semids[keyNo], semops, 3) == -1) {
			perror("semop"); std::exit(1);
		}
	}
	TESTPRINT("waited for semaphore %d of key %d\n", semNo, keyNo); // test
}



void SemManager::waitgeq(int keyNo, int semNo, int value)
{
	if (value == 0)
		return;

	TESTPRINT("waiting for semaphore %d of key %d to reach at least %d\n", semNo, keyNo, value); // test
	// decrement by value, wait for zero (necessary if semaphore was initially higher than value, which in our case doesn't happen), then increment by value
	semops[0].sem_num = semNo;
	semops[0].sem_op  = -value;
	semops[0].sem_flg = 0;
	semops[1].sem_num = semNo;
	semops[1].sem_op  = value;
	semops[1].sem_flg = 0;
	if (semop(semids[keyNo], semops, 2) == -1) {
		perror("semop"); std::exit(1);
	}
	TESTPRINT("waited for semaphore %d of key %d\n", semNo, keyNo); // test
}