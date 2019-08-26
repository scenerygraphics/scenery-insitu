// Generates changing data in same location of shared memory

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

int shmid;
float *str;
int cont;

int update(float *str)
{
	static int cnt = 0;
	// move each entry in array based on bits of cnt
	for (int i = 0; i < SIZE/sizeof(float); ++i) {
		str[i] += 0.02 * ((cnt & (1 << (i & ((1 << 16) - 1)))) ? 1 : -1);
	}

	++cnt;

	return cont; // whether to continue
}

void detach(int signal)
{
	printf("\n");
	cont = 0;
}

int main()
{
	key_t key = ftok("/tmp", RANK);
	printf("key:%d\n", key);

	shmid = shmget(key, SIZE, 0666|IPC_CREAT);

	if (shmid < 0) {
		printf("errno: %d\n", errno);
		exit(1);
	}

	str = (float*) shmat(shmid,(void*)0,0);
	// mmap(0, SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, shmid, 0);

	// printf("%d\n", str);

	for (int i = 0; i < SIZE/sizeof(float); ++i) {
		// printf("%d\n", i);
		str[i] = ((7*i) % 20 - 10) / 5.0;
	}

	std::cout << "Data written into memory: " << str[0] << std::endl;

	std::cin.get();

	signal(SIGINT, detach);
	cont = 1;
	while (update(str))
		usleep(10000);

	shmdt(str);
	shmctl(shmid, IPC_RMID, NULL);

}

