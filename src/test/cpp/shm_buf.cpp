// Communicate updates to shared memory through buffer

#include <iostream>
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
#include <signal.h>

#define SIZE 10000
#define RANK 0
#define INDLEN 2 // length of index in floats
#define BUFSIZE 10000

int shmid, bufid, oldid;
float *str, *buf; // buffer contains two elements, first is the current rank, second is whether processes are finished using old memory
int cont;
int bufrank;

void initstr()
{
	str[0] = buf[0]; // for now
	str[1] = buf[1];
        for (int i = INDLEN; i < SIZE/sizeof(float); ++i) {
                str[i] = ((7*i) % 20 - 10) / 5.0;
        }
}

void initbuf()
{
	buf[0] = buf[1] = 0;
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

	return cont; // whether to continue
}

void reall()
{
	static int toggle = 0;

	// generate new shared memory
	toggle ^= 1;
	bufrank = 4*RANK + 2 + toggle;

	// write it in buffer
	printf("Old rank: %d\n", (int) buf[0]);
	buf[0] = bufrank;
	if (str != NULL) str[0] = bufrank; // later remove
	printf("New rank: %d\n", (int) buf[0]);

	key_t key = ftok("/tmp", bufrank);
	printf("shm key:%d\n", key);

	if (str != NULL) {
		shmdt(str);
	}
	oldid = shmid;
	shmid = shmget(key, SIZE, 0666|IPC_CREAT);
	printf("shmid:%d\n", shmid);
	str = (float*) shmat(shmid,(void*)0,0);
	if (str == NULL) {
		printf("errno:%d\n", errno);
		exit(1);
	}

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
	buf = (float*) shmat(bufid,(void*)0,0);
	if (buf == NULL) {
		printf("errno:%d\n", errno);
		exit(1);
	}
	initbuf();
	// mmap(0, SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, shmid, 0);

	// printf("%d\n", str);

	str = NULL;
	reall();

	std::cout << "Data written into memory: " << str[0] << std::endl;

	std::cin.get();

	signal(SIGINT, detach);
	cont = 1;
	while (update())
		usleep(10000);

	shmdt(str);
	shmdt(buf);
	shmctl(shmid, IPC_RMID, NULL);
	shmctl(bufid, IPC_RMID, NULL);
	shmctl(oldid, IPC_RMID, NULL);
}

