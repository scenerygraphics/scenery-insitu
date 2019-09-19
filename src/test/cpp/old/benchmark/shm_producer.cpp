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
#include <future>
#include <mpi.h>
#include <inttypes.h>
// #include <time.h>

#define SIZE 100000
#define DURATION 500 // in seconds
#define UPDPER 5000
#define ITERS (DURATION*1000000L/UPDPER)
#define VERBOSE false
#define SHMRANK (rank+3)
#define SYNCHRONIZE true
#define DTYPE  long
#define ARRSIZE (SIZE/sizeof(DTYPE))

#define BARRIER() do { if (SYNCHRONIZE) MPI_Barrier(MPI_COMM_WORLD); } while (0)

int rank, size;

int shmid;
DTYPE *str;
bool cont, suspend;
struct timespec start;

void initstr() // modify this to copy old state to new array
{
	for (int i = 0; i < SIZE/sizeof(DTYPE); ++i) {
		str[i] = ((7*i) % 20 - 10) / 5.0;
	}
	if (clock_gettime( CLOCK_REALTIME, &start) < 0) {
		perror("clock_gettime"); exit(1);
	}
	str[0] = start.tv_sec * 1000000L + start.tv_nsec / 1000; // record time in milliseconds
}

int update()
{
	static int cnt = 0;

	// move each entry in array based on bits of cnt
	/*
	for (int i = 0; i < SIZE/sizeof(DTYPE); ++i) {
		str[i] += 0.02 * ((cnt & (1 << (i & ((1 << 4) - 1)))) ? 1 : -1);
	}
	*/

	if (clock_gettime( CLOCK_REALTIME, &start) < 0) {
		perror("clock_gettime"); exit(1);
	}
	str[0] = start.tv_sec * 1000000L + start.tv_nsec / 1000; // record time in milliseconds

	++cnt;

	cont = cont && cnt < ITERS;
	return cont; // whether to continue
}

void terminate()
{

	std::cout << "rank " << rank << " finished waiting" << std::endl;

	shmdt(str);
	str = NULL;

	std::cout << "rank " << rank << " freed memory" << std::endl;

	// BARRIER();

	// std::cout << "rank " << rank << " with offset " << offset << " alloc: " << ((long) alloc) << std::endl;

	shmctl(shmid, IPC_RMID, NULL);

	std::cout << "rank " << rank << " deleted alloc" << std::endl;
}

// input and message handling
void msgloop()
{
	if (rank == 0) { // assuming producer is called first in mpi
		// cont = true;
		char c;

		do {
				std::cout << "Running..." << std::endl;
				std::cin.get();
				suspend = true;
				std::cout << "Continue? ";
				std::cin >> c;
				std::cout << std::endl;
				suspend = false;
				if (c != 'y')
						cont = false;
				std::cin.get();
		} while (cont);

	} else {
		int flag;
		MPI_Status stat;

		std::cout << "rank " << rank << " waiting for 0" << std::endl;
		MPI_Probe(0, MPI_ANY_TAG, MPI_COMM_WORLD, &stat);
		std::cout << "rank " << rank << " received message" << std::endl;

		cont = false;
	}

	std::cout << "Exiting rank " << rank << std::endl;
	std::cout << "suspend: " << suspend << "\tcont: " << cont << std::endl;

	if (rank == 0) {
		// alert other processes to finish
		MPI_Request req;
		for (int i = 1; i < size; ++i) // do nothing if size == 1
			MPI_Isend(NULL, 0, MPI_INT, i, MPI_TAG_UB, MPI_COMM_WORLD, &req), std::cout << "sent message to " << i << std::endl;
	}
}

int main(int argc, char *argv[])
{
	MPI_Init(&argc, &argv);

	MPI_Comm_size(MPI_COMM_WORLD, &size);
	MPI_Comm_rank(MPI_COMM_WORLD, &rank);

	std::cout << "starting producer with rank " << rank << " of size " << size << std::endl;


	key_t key = ftok("/tmp", SHMRANK);
	printf("key:%d\n", key);
	shmid = shmget(key, SIZE, 0666|IPC_CREAT);

	if (shmid < 0) {
		printf("errno: %d\n", errno);
		exit(1);
	}

	str = (DTYPE*) shmat(shmid, NULL, 0);
	initstr();

	// BARRIER();

	std::cout << "Data written into memory: " << str[0] << std::endl;

	// std::cin.get();

	cont = true;
	suspend = false;

	// BARRIER();

	std::future<void> out = std::async(std::launch::async, msgloop);

	// signal(SIGINT, detach);
	while (suspend || update())
		usleep(UPDPER);

	out.wait();

	terminate();

	// BARRIER();

	MPI_Finalize();
}

/*
int main()
{

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
*/