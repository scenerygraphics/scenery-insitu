// Communicate updates to shared memory through buffer
// Use semaphores to communicate when consumer has stopped using old memory

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
#define NSEM 1
#define SEMLEN 2

#define GET_RANK(toggle) (4*RANK+2+(toggle))

int shmid, bufid, oldid = -1;
float *str;
int  *buf; // buffer contains two elements, first is the current rank, second is whether processes are finished using old memory
int cont;
int bufrank;

int semid[SEMLEN]; // semaphore ids for each rank (may be stored in single semaphore with nsems = 2)
union semun sem_attr;
int toggle = 0, delwait = 0; // toggle: which memory is currently used, delwait: whether to wait for consumer to release old memory

void initstr()
{
	// str[0] = buf[0]; // for now
	// str[1] = buf[1];
        for (int i = INDLEN; i < SIZE/sizeof(float); ++i) {
                str[i] = ((7*i) % 20 - 10) / 5.0;
        }
}

void initbuf()
{
	buf[0] = buf[1] = 0;
}

void initsem()
{
	for (int i = 0; i < SEMLEN; ++i) {
		key_t key = ftok("/tmp", GET_RANK(i));
		std::cout << "sem key: " << key << std::endl;

		semid[i] = semget(key, NSEM, 0666|IPC_CREAT);
		sem_attr.val = 1;
		semctl(semid[i], 0, SETVAL, sem_attr);
		std::cout << "created semid " << semid[i] << " of value 1" << std::endl;
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

	/*
	// if processes have finished reading oldid
	if (buf[1] == 1) {
		shmctl(oldid, IPC_RMID, NULL);
	}
	*/
	if (delwait) { // maybe have parallel thread, instead of checking it synchronously here
		// check if processes have finished reading oldid
		int semval = semctl(semid[1^toggle], 0, GETVAL);
		std::cout << "read value " << semval << " from semaphore " << (1^toggle) << std::endl;
		if (semval > 0) {
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
	// generate new shared memory
	toggle ^= 1;
	bufrank = GET_RANK(toggle);

	// write it in buffer
	printf("Old rank: %d\n", (int) buf[0]);
	buf[0] = bufrank;
	// if (str != NULL) str[0] = bufrank; // later remove
	printf("New rank: %d\n", (int) buf[0]);

	key_t key = ftok("/tmp", bufrank);
	printf("shm key:%d\n", key);

	if (str != NULL) {
		shmdt(str);
		delwait = 1; // may also communicate change to consumer with another semaphore
		std::cout << "waiting to delete " << shmid << " for rank " << (1^toggle) << std::endl;
	}
	oldid = shmid;
	shmid = shmget(key, SIZE, 0666|IPC_CREAT);
	printf("shmid:%d\n", shmid);
	str = (float*) shmat(shmid,(void*)0,0);
	if (str == NULL) {
		printf("errno:%d\n", errno);
		exit(1);
	}
	printf("buf:%d\tstr:%d\n", buf, str);

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
	key_t key = ftok("/tmp", RANK);
	printf("key:%d\n", key);

	bufid = shmget(key, SIZE, 0666|IPC_CREAT);
	printf("bufid:%d\n", bufid);
	buf = (int*) shmat(bufid,(void*)0,0);
	if (buf == NULL) {
		printf("errno:%d\n", errno);
		exit(1);
	}
	initbuf();

	str = NULL;
	reall();
	initsem();

	std::cout << "Data written into memory: " << str[0] << std::endl;
	signal(SIGINT, detach);

	std::cin.get();

	cont = 1;
	while (update())
		usleep(10000);

	shmdt(str);
	shmdt(buf);
	shmctl(shmid, IPC_RMID, NULL);
	shmctl(bufid, IPC_RMID, NULL);
	if (oldid != -1)
		shmctl(oldid, IPC_RMID, NULL);

	for (int i = 0; i < SEMLEN; ++i)
		semctl(semid[i], 0, IPC_RMID);
}

