// Eliminate shared memory, just in case anything goes wrong

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

#define SIZE 2024
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

	shmdt(str);
	shmctl(shmid, IPC_RMID, NULL);
}
