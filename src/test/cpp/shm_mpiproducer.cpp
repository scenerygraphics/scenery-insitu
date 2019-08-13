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

#define SIZE(i) (i ? 40000 : 10000)
#define UPDPER 20000
#define REALLPER 50
#define VERBOSE false
#define COPYSTR true
#define SHMRANK (rank+3)
#define SYNCHRONIZE true

#define BARRIER() do { if (SYNCHRONIZE) MPI_Barrier(MPI_COMM_WORLD); } while (0)

#define PNAME(isProp) ((isProp) ? "/etc" : "/tmp")

#define PTR(isProp) ((isProp) ? ((void *) props) : ((void *) data))
#define PTR1(isProp) ((isProp) ? ((void *) props1) : ((void *) data1))

int rank, size;

bool cont, suspend;
float *data = NULL, *data1 = NULL;
double *props = NULL, *props1 = NULL;
ShmAllocator *alloc[2];

void initdata()
{
	if (!COPYSTR || data1 == NULL) {
		for (int i = 0; i < SIZE(0)/sizeof(float); ++i) {
			data[i] = ((7*i) % 20 - 10) / 5.0;
		}
	} else {
		for (int i = 0; i < SIZE(0)/sizeof(float); ++i) {
			data[i] = data1[i];
		}
	}
}

void initprops()
{
	if (!COPYSTR || props1 == NULL) {
		for (int i = 0; i < SIZE(0)/sizeof(float); ++i) {
			props[i] = i; // ((7*i) % 20 - 10) / 5.0;
		}
	} else {
		for (int i = 0; i < SIZE(0)/sizeof(float); ++i) {
			props[i] = props1[i];
		}
	}
}

void detach(int signal);
void reall(int isProp);

void reall()
{
	reall(0);
	reall(1);
}

int update()
{
	static int cnt = 0;

	// move each entry in array based on bits of cnt
	float vel; // increment, just as placeholder for properties
	for (int i = 0; i < SIZE(0)/sizeof(float); ++i) {
		vel = 0.02 * ((cnt & (1 << (i & ((1 << 4) - 1)))) ? 1 : -1);
		data[i] += vel;
		vel = vel * 1000000 / UPDPER;
		props[6*(i/3)+i%3+3] = 1; // (vel - str[1][6*(i/3)+i%3]) * 1000000 / UPDPER; // acceleration
		props[6*(i/3)+i%3] = vel; // velocity
	}

	++cnt;

	if (cnt % REALLPER == 0)
		reall();
	// 	detach(0);

	return cont; // whether to continue
}

void reall(int isProp)
{
	void *ptr = alloc[isProp]->shm_alloc(SIZE(isProp));
	if (isProp) {
		props1 = props;
		props = (double *) ptr;
		initprops();
	} else {
		data1 = data;
		data = (float *) ptr;
		initdata();
	}

	// testing
	if (PTR1(isProp) != NULL) {
		if (VERBOSE) std::cout << "\tallocating from heap" << std::endl;
		void *ptr = alloc[isProp]->shm_alloc(10);
		if (VERBOSE) std::cout << "\tallocated " << ((long) ptr) << " from heap" << std::endl;
		alloc[isProp]->shm_free(ptr);
		if (VERBOSE) std::cout << "\tfreed " << ((long) ptr) << std::endl;
	}

	// detach from old shm, release semaphore
	alloc[isProp]->shm_free(PTR1(isProp)); // need not check for null
	if (isProp)
		props1 = NULL;
	else
		data1 = NULL;
}

void detach(int signal)
{
	char c;
	std::cout << "Reallocate? ";
	std::cin >> c;
	if (c == 'y') {
		reall();
		std::cout << "Data written into memory: " << data[0] << std::endl;
	} else {
		printf("\n");
		cont = false;
	}
}

void terminate()
{

	std::cout << "rank " << rank << " finished waiting" << std::endl;

	for (int i = 0; i < 2; ++i) {
		alloc[i]->shm_free(PTR(i));
	}
	data = NULL;
	props = NULL;

	std::cout << "rank " << rank << " freed memory" << std::endl;

	// BARRIER();

	// std::cout << "rank " << rank << " with offset " << offset << " alloc: " << ((long) alloc) << std::endl;

	for (int i = 0; i < 2; ++i)
		delete alloc[i];

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

	for (int i = 0; i < 2; ++i)
		alloc[i] = new ShmAllocator(PNAME(i), SHMRANK, VERBOSE);

	// str = NULL;
	reall();

	// BARRIER();

	std::cout << "Data written into memory: " << data[0] << std::endl;

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
