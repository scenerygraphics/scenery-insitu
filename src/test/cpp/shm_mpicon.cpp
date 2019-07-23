// Test producer using allocator class

#include <iostream>
#include <future>
#include <unistd.h>
#include <fcntl.h>
#include <stdlib.h>
#include <stdio.h>
#include <signal.h>
#include <mpi.h>

#include "ShmBuffer.hpp"

#define SIZE 10000
#define UPDPER 5000
#define REALLPER 333
#define VERBOSE true
#define COPYSTR true
#define NOCONSUMER false
#define SHMRANK (rank+3)
#define SYNCHRONIZE true

#define BARRIER() do { if (SYNCHRONIZE) MPI_Barrier(MPI_COMM_WORLD); } while (0)

int rank, size, offset = 0;

bool cont, suspend;
float *str = NULL, *str1 = NULL;
ShmBuffer *buf;

void detach(int signal);
void reall();

int update()
{
	static int cnt = 0;

	// move each entry in array based on bits of cnt
	float sum = 0;
	for (int i = 0; i < SIZE/sizeof(float); ++i) {
		sum += str[i]*((i*i+1)&3);
	}
	if (cnt % REALLPER == 0)
		std::cout << "val: " << str[5] << std::endl;

	++cnt;

	return cont; // whether to continue
}

void detach(int signal)
{
	char c;
	std::cout << "Reallocate? ";
	std::cin >> c;
	if (c == 'y') {
		// reall();
		std::cout << "Data written into memory: " << str[0] << std::endl;
	} else {
		printf("\n");
		cont = false;
	}
}

void reall()
{
	buf->update_key();
	str = (float *) buf->attach();
	std::cout << "attached to new" << std::endl;
	buf->detach(false);
}

void terminate()
{

	std::cout << "rank " << rank << " with offset " << offset << " finished waiting" << std::endl;

	buf->detach(true);
	str = NULL;

	std::cout << "rank " << rank << " with offset " << offset << " buf: " << ((long) buf) << std::endl;

	BARRIER();

	delete buf;

	std::cout << "rank " << rank << " with offset " << offset << " deleted buf" << std::endl;
}

// input handling
void loop()
{
	if (rank == 0 && offset == 0) { // assuming producer is called first in mpi
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

		std::cout << "rank " << rank << " waiting for " << (size - offset) << std::endl;
		MPI_Probe(size - offset, MPI_ANY_TAG, MPI_COMM_WORLD, &stat);
		std::cout << "rank " << rank << " waited for " << (size - offset) << std::endl;

		cont = false;
	}

	std::cout << "Exiting rank " << rank << std::endl;
	std::cout << "suspend: " << suspend << "\tcont: " << cont << std::endl;

	// terminate();

	if (rank == 0 && offset == 0 && size > 0) {
		// alert other processes to finish
		MPI_Request req;
		for (int i = 1; i < size; ++i)
			MPI_Isend(NULL, 0, MPI_INT, i + offset, MPI_TAG_UB, MPI_COMM_WORLD, &req), std::cout << "sent message to " << i << std::endl;
		if (!NOCONSUMER)
			for (int i = 0; i < size; ++i)
				MPI_Isend(NULL, 0, MPI_INT, i + size - offset, MPI_TAG_UB, MPI_COMM_WORLD, &req);
	}
}

int main(int argc, char *argv[])
{
	MPI_Init(&argc, &argv);

	MPI_Comm_size(MPI_COMM_WORLD, &size);
	MPI_Comm_rank(MPI_COMM_WORLD, &rank);
	if (!NOCONSUMER && size > 1) {
		size /= 2; // perhaps also communicate to consumers
		offset = size * (rank / size);
		rank %= size; // assuming for now that mpi assigns ranks in order to different programs
	}

	BARRIER(); // wait for producer to initialize memory

	std::cout << "starting consumer with rank " << rank << " of size " << size << " and offset " << offset << std::endl;

	buf = new ShmBuffer("/tmp", SHMRANK, SIZE, VERBOSE);

	str = NULL;
	reall();

	BARRIER(); // signal producer that it can take input now

	cont = true;
	suspend = false;

	std::future<void> out = std::async(std::launch::async, loop);

	// signal(SIGINT, detach);
	while (suspend || update())
		usleep(UPDPER);

	out.wait();

	terminate();

	BARRIER();

	MPI_Finalize();
}
