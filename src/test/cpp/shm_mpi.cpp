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
#include <mpi.h>

#define SIZE 10000
#define RANK 0
#define INDLEN 20 // length of index in floats

int shmid;
float *str;
int memloc, size;
int world_size, world_rank;

int update(float *str)
{
	static int cnt = 0;
	// move each entry in array based on bits of cnt
	for (int i = INDLEN; i < SIZE/sizeof(float); ++i) {
		str[i] += 0.02 * ((cnt & (1 << (i & ((1 << 4) - 1)))) ? 1 : -1);
	}

	++cnt;

	int flag;
	MPI_Status stat;
	MPI_Iprobe(0, MPI_ANY_TAG, MPI_COMM_WORLD, &flag, &stat); // check for message
	return !flag;
}

void reall()
{
	// generate new address
	size = 45;
	memloc = (13 * memloc + 27) % (SIZE/sizeof(float) - size - INDLEN) + INDLEN;

	// write it and size in index
	str[0] = memloc;
	str[1] = size;
}

int detach()
{
	char c;
	std::cout << "Reallocate? ";
	std::cin >> c;

	if (c == 'y') {
		reall();
		std::cout << "Data written into memory: " << str[memloc] << std::endl;
		std::cout << "Data location: " << memloc << std::endl;

		return 1;
	} else {
		MPI_Request req;
		MPI_Isend(NULL, 0, MPI_INT, 1, MPI_TAG_UB, MPI_COMM_WORLD, &req); // send call to terminate

		return 0;
	}
}

void term(int signal)
{
	MPI_Barrier(MPI_COMM_WORLD);

	shmdt(str);
	if (!world_rank)
		shmctl(shmid, IPC_RMID, NULL);

	MPI_Finalize();
}

int main(int argc, char *argv[])
{
	MPI_Init(&argc, &argv);

	MPI_Comm_size(MPI_COMM_WORLD, &world_size);
	MPI_Comm_rank(MPI_COMM_WORLD, &world_rank);
	printf("Rank: %d out of %d\n", world_rank, world_size);

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

	// signal(SIGTERM, term);

	if (world_rank == 0) {

		for (int i = INDLEN; i < SIZE/sizeof(float); ++i) {
			// printf("%d\n", i);
			str[i] = ((7*i) % 20 - 10) / 5.0;
		}

		memloc = 0;
		reall();

		std::cout << "Data written into memory: " << str[memloc] << std::endl;
		std::cout << "Data location: " << memloc << std::endl;

		std::cout << "Update? ";
		std::cin.get();

		MPI_Barrier(MPI_COMM_WORLD);

		while (detach());

	} else {
		MPI_Barrier(MPI_COMM_WORLD);

		while (update(str))
			usleep(10000);
	}

	term(SIGTERM);
}

