// Communicate updates to shared memory through buffer
// Use semaphores to communicate when consumer has stopped using old memory
// Each rank keeps two semaphores, 0th semaphore for consumer, 1st semaphore for producer
// Producer waits until consumer stops using old memory, when 0th semaphore becomes 0
// Consumer waits until producer starts using new memory, when 1st semaphore becomes 0

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
#include <signal.h>

#define SIZE 2024
#define RANK 3
#define INDLEN 0 // length of index in floats
#define BUFSIZE 2024
#define NSEM 2 // number of semaphores per key; 0th semaphore for no of consumers, 1st semaphore for no of producers not using shm
#define NRANK 2 // number of ranks used per processor
#define SEMINIT 0 // initial value of semaphores
#define SEMOPS 10 // max number of semops to perform at a time

#define GET_RANK(toggle) (2*RANK+1+(toggle))

int shmid, oldid = -1;
float *str;
int cont;
int shmrank;

int semid[NRANK]; // semaphore ids for each rank
union semun sem_attr;
struct sembuf semops[SEMOPS];
int toggle = 0, delwait = 0; // toggle: which memory is currently used, delwait: whether to wait for consumer to release old memory

void initstr() // modify this to copy old state to new array
{
	for (int i = INDLEN; i < SIZE/sizeof(float); ++i) {
		str[i] = ((7*i) % 20 - 10) / 5.0;
	}
}

void initsem()
{
	for (int i = 0; i < NRANK; ++i) {
		key_t key = ftok("/tmp", GET_RANK(i));
		std::cout << "sem key: " << key << std::endl;

		semid[i] = semget(key, NSEM, 0666|IPC_CREAT);

		// currently, no consumers or producers
		sem_attr.val = 0;
		semctl(semid[i], 0, SETVAL, sem_attr); // no consumers -> 0
		sem_attr.val = 1;
		semctl(semid[i], 1, SETVAL, sem_attr); // no producers -> 1
		std::cout << "created semid " << semid[i] << std::endl;
	}
}

int update()
{
	static int cnt = 0;
	// move each entry in array based on bits of cnt
	for (int i = INDLEN; i < SIZE/sizeof(float); ++i) {
		str[i] += 0.02 * ((cnt & (1 << (i & ((1 << 4) - 1)))) ? 1 : -1);
	}

	++cnt;

	if (delwait) { // maybe have parallel thread, instead of checking it synchronously here
		// check if consumers have finished reading oldid
		int semval = semctl(semid[1^toggle], 0, GETVAL);
		// std::cout << "read value " << semval << " from semaphore 0 of rank " << (1^toggle) << std::endl;
		if (semval == SEMINIT) {
			std::cout << "deleting rank " << (1^toggle) << " with shmid " << oldid << std::endl;
			shmctl(oldid, IPC_RMID, NULL); // assuming consumers detect change faster than program reallocates, so that oldid is the last id they use
			delwait = 0;
			oldid = -1;
		}
	}

	return cont; // whether to continue
}

void reall()
{
	// generate new key
	toggle ^= 1;

	printf("Old rank: %d\n", shmrank);
	shmrank = GET_RANK(toggle);
	printf("New rank: %d\n", shmrank);

	key_t key = ftok("/tmp", shmrank);
	printf("shm key:%d\n", key);

	// detach from old shm, release semaphore
	if (str != NULL) {
		shmdt(str);
		delwait = 1; // may also communicate change to consumer with another semaphore
		std::cout << "waiting to delete " << shmid << " for rank " << (1^toggle) << std::endl;

		// sem_attr.val = SEMINIT;
		// semctl(semid[1^toggle], 1, SETVAL, sem_attr);
		semops[0].sem_num = 1;
		semops[0].sem_op  = 1;
		semops[0].sem_flg = 0;
		if (semop(semid[1^toggle], semops, 1) == -1) {
			perror("semop"); exit(1);
		}
		std::cout << "incremented semaphore 1 of rank " << (1^toggle) << std::endl;
	}

	oldid = shmid;
	shmid = shmget(key, SIZE, 0666|IPC_CREAT);
	printf("shmid:%d\n", shmid);

	// attach to new shm, set semaphore
	str = (float*) shmat(shmid,(void*)0,0);
	if (str == NULL) {
		perror("shmat"); exit(1);
	}
	printf("str:%d\n", str);

	// sem_attr.val = SEMINIT - 1;
	// semctl(semid[toggle], 1, SETVAL, sem_attr);
	semops[0].sem_num = 1;
	semops[0].sem_op  = -1;
	semops[0].sem_flg = 0;
	if (semop(semid[toggle], semops, 1) == -1) {
		perror("semop"); exit(1);
	}
	std::cout << "decremented semaphore 1 of rank " << toggle << std::endl;

	initstr();

}

void detach(int signal)
{
	char c;
	std::cout << "Reallocate? ";
	std::cin >> c;
	if (c == 'y') {
		reall();
		std::cout << "Data written into memory: " << str[0] << std::endl;
	} else {
		printf("\n");
		cont = 0;
	}
}

int main()
{
	initsem();

	str = NULL;
	reall();

	std::cout << "Data written into memory: " << str[0] << std::endl;

	std::cin.get();

	signal(SIGINT, detach);

	cont = 1;
	while (update())
		usleep(10000);

	shmdt(str);
	shmctl(shmid, IPC_RMID, NULL);
	if (oldid != -1)
		shmctl(oldid, IPC_RMID, NULL);

	for (int i = 0; i < NRANK; ++i)
		semctl(semid[i], 0, IPC_RMID);
}

