// Generates changing data in different locations of shared memory
// Simply communicate starting index and length of array in shared memory when allocating,
// Broadcast reallocations likewise, and wait for read locks before deallocating
// Or simply keep track of written and read segments of memory, realloc outside segments read
// No need to wait for read locks when writing or vice versa

// Producer manages memory, keeping e.g. semaphores for each segment,
// broadcasting each allocation to readers, deallocating once all processes using segment have freed
// Keep track of offsets of each segment in some fixed index, which are updated in realloc and checked while reading
// Possibly broadcast or record updates too by timestamps, so reader only iterates through array once update occurs

// Segment format: metadata (e.g. update, length) + array of particles each as a vector of positions and properties
// General I/O: lookup segment offset, go to offset, check if updated, return buffer for array
// Specific I/O: go through array of particles, process them possibly without copying
// Perhaps think of segments as files, or at least objects or arrays allocated


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
#define INDLEN 0 // length of index in floats
#define BUFSIZE 20

int shmid, bufid, oldid;
float *str, *buf; // buffer contains two elements, first is the current rank, second is whether processes are finished using old memory
int cont;
int bufrank;

void initstr()
{
        for (int i = 0; i < SIZE/sizeof(float); ++i) {
                str[i] = ((7*i) % 20 - 10) / 5.0;
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

	// if processes have finished reading oldid
	if (buf[1] == 1) {
		shmctl(oldid, IPC_RMID, NULL);
	}

	return cont; // whether to continue
}

void reall()
{
	static int toggle = 0;

	// generate new shared memory
	toggle ^= 1;
	myrank = 2*RANK + toggle;
	key_t key = ftok("/tmp", myrank);
	printf("shm key:%d\n", key);

	if (str == NULL) {
		// first call, reset buffer
		buf[0] = buf[1] = 0;
	} else {
		shmdt(str);
	}
	oldid = shmid;
	shmid = shmget(key, SIZE, 0666|IPC_CREAT);
	str = (float*) shmat(shmid,(void*)0,0);
	initstr();

	// write it in buffer
	buf[0] = myrank;
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

	buf = (float*) shmat(shmid,(void*)0,0);
	// mmap(0, SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, shmid, 0);

	// printf("%d\n", str);

	str = NULL;
	reall();

	std::cout << "Data written into memory: " << str[0] << std::endl;

	std::cin.get();

	signal(SIGINT, detach);
	cont = 1;
	while (update(str))
		usleep(10000);

	shmctl(shmid, IPC_RMID, NULL);

}

