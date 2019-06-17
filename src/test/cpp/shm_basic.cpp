// Generates unchanging data in shared memory

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

#define SIZE 2048
#define RANK 0

int main()
{
	key_t key = ftok("/tmp", RANK);
	printf("key:%d\n", key);

	int shmid = shmget(key, SIZE, 0666|IPC_CREAT);

	if (shmid < 0) {
		printf("errno: %d\n", errno);
		exit(1);
	}

	float *str = (float*) shmat(shmid,(void*)0,0);
	// mmap(0, SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, shmid, 0);

	// printf("%d\n", str);

	for (int i = 0; i < SIZE/sizeof(float); ++i) {
		// printf("%d\n", i);
		str[i] = (7*i) % 20 - 10;
	}

	std::cout << "Data written into memory: " << str[0] << std::endl;

	std::cin.get();

	shmdt(str);
	shmctl(shmid, IPC_RMID, NULL);
}
