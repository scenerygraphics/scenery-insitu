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

#define DTYPE double
#define VERBOSE false
#define COPYSTR true
#define SHMRANK (rank)
#define SYNCHRONIZE true
#define UPDPER 50
#define REALLPER 1
#define PRINTPER 7777

// simulate simple harmonic oscillator
#define GRIDLEN 11
#define CENTER(i) (1.5*(2*i-(GRIDLEN-1))/(GRIDLEN-1))
#define NUMPARS (GRIDLEN*GRIDLEN*GRIDLEN)
#define SIZE(i) (i ? 6*NUMPARS*sizeof(DTYPE) : 3*NUMPARS*sizeof(DTYPE))
#define OSCPER .05 // period of oscillation in seconds
#define FOURPISQ 39.4784  // 4pi^2
#define DT (UPDPER/1000000.) // time increment
#define POS(i, j) str[0][3*i+j]   // jth component of position vector of ith particle
#define VEL(i, j) str[1][6*i+j]   // jth component of velocity vector of ith particle
#define ACC(i, j) str[1][6*i+j+3] // jth component of acceleration vector of ith particle

#define BARRIER() do { if (SYNCHRONIZE) MPI_Barrier(MPI_COMM_WORLD); } while (0)

#define PNAME(isProp) ((isProp) ? "/home" : "/tmp")

#define PTR(isProp) str[isProp] // ((isProp) ? ((void *) props) : ((void *) data))
#define PTR1(isProp) str1[isProp] // ((isProp) ? ((void *) props1) : ((void *) data1))

int rank, size;

bool cont, suspend;
DTYPE *str[] = {NULL, NULL}, *str1[] = {NULL, NULL};
ShmAllocator *alloc[2];

void initptr(int isProp)
{
	if (!COPYSTR || PTR1(isProp) == NULL) {
		if (isProp) {
			// initially no velocity or acceleration
			for (int i = 0; i < SIZE(isProp)/sizeof(DTYPE); ++i) {
				PTR(isProp)[i] = 0;
			}
		} else {
			// initially points arranged in a grid
			for (int i = 0; i < GRIDLEN; ++i) {
				for (int j = 0; j < GRIDLEN; ++j) {
					for (int k = 0; k < GRIDLEN; ++k) {
						int p = GRIDLEN*(GRIDLEN*i+j)+k; // particle number
						POS(p, 0) = CENTER(i);
						POS(p, 1) = CENTER(j);
						POS(p, 2) = CENTER(k);
					}
				}
			}
		}
	} else {
		for (int i = 0; i < SIZE(isProp)/sizeof(DTYPE); ++i) {
			PTR(isProp)[i] = PTR1(isProp)[i];
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
	/*
	float vel; // increment, just as placeholder for properties
	for (int i = 0; i < SIZE(0)/sizeof(DTYPE); ++i) {
		vel = 0.02 * ((cnt & (1 << (i & ((1 << 4) - 1)))) ? 1 : -1);
		data[i] += vel;
		vel = vel * 1000000 / UPDPER;
		props[6*(i/3)+i%3+3] = 1; // (vel - str[1][6*(i/3)+i%3]) * 1000000 / UPDPER; // acceleration
		props[6*(i/3)+i%3] = vel; // velocity
	}
	*/

	for (int i = 0; i < NUMPARS; ++i) {
		for (int j = 0; j < 3; ++j) {
			ACC(i, j) = -POS(i, j) * FOURPISQ / OSCPER / OSCPER; // compute acceleration here to avoid initialization
			VEL(i, j) = VEL(i, j) + DT*ACC(i, j);
			POS(i, j) = POS(i, j) + DT*VEL(i, j);
		}
	}

	++cnt;

	if (cnt % REALLPER == 0)
		reall();
	// 	detach(0);

	if (cnt % PRINTPER == 0) {
		printf("Position:\t(%lf, %lf, %lf)\n", POS(0,0), POS(0,1), POS(0,2));
		printf("Velocity:\t(%lf, %lf, %lf)\n", VEL(0,0), VEL(0,1), VEL(0,2));
		printf("Acceleration:\t(%lf, %lf, %lf)\n", ACC(0,0), ACC(0,1), ACC(0,2));
	}

	return cont; // whether to continue
}

void reall(int isProp)
{
	void *ptr = alloc[isProp]->shm_alloc(SIZE(isProp));
	PTR1(isProp) = PTR(isProp);
	PTR(isProp) = (DTYPE *) ptr;
	initptr(isProp);

	// testing heap allocation
	if (PTR1(isProp) != NULL) {
		if (VERBOSE) std::cout << "\tallocating from heap" << std::endl;
		void *ptr = alloc[isProp]->shm_alloc(10);
		if (VERBOSE) std::cout << "\tallocated " << ((long) ptr) << " from heap" << std::endl;
		alloc[isProp]->shm_free(ptr);
		if (VERBOSE) std::cout << "\tfreed " << ((long) ptr) << std::endl;
	}

	// detach from old shm, release semaphore
	alloc[isProp]->shm_free(PTR1(isProp)); // need not check for null
	PTR1(isProp) = NULL;
}

void detach(int signal)
{
	char c;
	std::cout << "Reallocate? ";
	std::cin >> c;
	if (c == 'y') {
		reall();
		std::cout << "Data written into memory: " << PTR(0)[0] << std::endl;
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
		PTR(i) = NULL;
	}

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

	std::cout << "Data written into memory: " << PTR(0)[0] << std::endl;

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
