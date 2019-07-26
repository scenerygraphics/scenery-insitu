// Test producer using allocator class

#include <iostream>
#include <future>
#include <unistd.h>
#include <fcntl.h>
#include <stdlib.h>
#include <stdio.h>
#include <signal.h>
#include <mpi.h>

#include "ShmAllocator.hpp"

#define SIZE 10000
#define UPDPER 5000
#define REALLPER 50
#define VERBOSE false
#define COPYSTR true
#define SHMRANK (rank+3)
#define SYNCHRONIZE true

#define BARRIER() do { if (SYNCHRONIZE) MPI_Barrier(MPI_COMM_WORLD); } while (0)

int rank, size;

bool cont, suspend;
float *str = NULL, *str1 = NULL;
ShmAllocator *alloc;

void initstr() // modify this to copy old state to new array
{
	if (!COPYSTR || str1 == NULL) {
		for (int i = 0; i < SIZE/sizeof(float); ++i) {
			str[i] = ((7*i) % 20 - 10) / 5.0;
		}
	} else {
		for (int i = 0; i < SIZE/sizeof(float); ++i) {
			str[i] = str1[i];
		}
	}
}

void detach(int signal);
void reall();

int update()
{
	static int cnt = 0;

	// move each entry in array based on bits of cnt
	for (int i = 0; i < SIZE/sizeof(float); ++i) {
		str[i] += 0.02 * ((cnt & (1 << (i & ((1 << 4) - 1)))) ? 1 : -1);
	}

	++cnt;

	if (cnt % REALLPER == 0)
		reall();
	// 	detach(0);

	return cont; // whether to continue
}

void reall()
{
	// generate new key
	str1 = str;

	str = (float *) alloc->shm_alloc(SIZE);
	initstr();

	// detach from old shm, release semaphore // detach after attaching to new, and copy from old to new
	alloc->shm_free(str1); // need not check for null
	str1 = NULL;
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
		cont = false;
	}
}

void terminate()
{

	std::cout << "rank " << rank << " finished waiting" << std::endl;

	alloc->shm_free(str);
	str = NULL;

	std::cout << "rank " << rank << " freed memory" << std::endl;

	// BARRIER();

	// std::cout << "rank " << rank << " with offset " << offset << " alloc: " << ((long) alloc) << std::endl;

	delete alloc;

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

	alloc = new ShmAllocator("/tmp", SHMRANK, VERBOSE);

	str = NULL;
	reall();

	// BARRIER();

	std::cout << "Data written into memory: " << str[0] << std::endl;

	std::cin.get();

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