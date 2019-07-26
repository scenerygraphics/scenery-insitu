// Send and receive data through MPI buffers

#define _OPEN_SYS
#include <iostream>
#include <future>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include <stdlib.h>
#include <stdio.h>
#include <signal.h>
#include <mpi.h>

#define SIZE 10
#define ARRSIZE (SIZE/sizeof(float))
#define UPDPER 5000
#define REALLPER 50
#define PRINTPER 333
#define VERBOSE false
#define COPYSTR true
#define NOCONSUMER false
#define SHMRANK (rank+3)
#define SYNCHRONIZE true

#define BARRIER() do { if (SYNCHRONIZE) MPI_Barrier(MPI_COMM_WORLD); } while (0)

#define PARTNER ((rank+size/2)%size)
#define ISPRODUCER (rank < size/2)

int rank, size;
// int fd[2];
int fd;

bool cont, suspend;
float *str = NULL, *str1 = NULL;
char fifoname[] = "/tmp/temp0.fifo";

#define SETFIFONAME() do { fifoname[9] += (rank % (size/2)); } while (0)

void loop()
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

		std::cout << "rank " << rank << " waiting for " << 0 << std::endl;
		MPI_Probe(0, MPI_ANY_TAG, MPI_COMM_WORLD, &stat);
		std::cout << "rank " << rank << " waited for " << 0 << std::endl;

		cont = false;
	}

	std::cout << "Exiting rank " << rank << std::endl;
	std::cout << "suspend: " << suspend << "\tcont: " << cont << std::endl;

	// terminate();

	if (rank == 0) {
		// alert other processes to finish
		MPI_Request req;
		for (int i = 1; i < size; ++i)
			MPI_Isend(NULL, 0, MPI_INT, i, MPI_TAG_UB, MPI_COMM_WORLD, &req), std::cout << "sent message to " << i << std::endl;
	}
}

// producer

void prod_initstr() // modify this to copy old state to new array
{
	if (!COPYSTR || str1 == NULL) {
		for (int i = 0; i < ARRSIZE; ++i) {
			str[i] = ((7*i) % 20 - 10) / 5.0;
		}
	} else {
		for (int i = 0; i < ARRSIZE; ++i) {
			str[i] = str1[i];
		}
	}

	// send to consumer
	std::cout << "sending to consumer" << std::endl;
	// write(fd[1], str, SIZE);
	write(fd, str, SIZE);
}

void prod_reall() // later switch to new pipe here possibly
{
	// generate new key
	str1 = str;

	str = new float[ARRSIZE]; // (float *) alloc->shm_alloc(SIZE);
	prod_initstr();

	// detach from old shm, release semaphore // detach after attaching to new, and copy from old to new
	delete str1; // alloc->shm_free(str1); // need not check for null
}

bool prod_update()
{
	static int cnt = 0;

	// move each entry in array based on bits of cnt
	for (int i = 0; i < ARRSIZE; ++i) {
		str[i] += 0.02 * ((cnt & (1 << (i & ((1 << 4) - 1)))) ? 1 : -1);
	}

	++cnt;

	if (cnt % REALLPER == 0)
		prod_reall();

	// send to consumer
	if (cnt % PRINTPER == 0)
		std::cout << "sending to " << fd << std::endl;
	// write(fd[1], str, SIZE);
	write(fd, str, SIZE);

	return cont; // whether to continue
}

void producer()
{
	std::cout << "starting producer with rank " << rank << " of size " << size << std::endl;

	// create pipe
	SETFIFONAME();
	if (mkfifo(fifoname, 0666)) {
		perror("mkfifo"); exit(1);
	}

	std::cout << "made fifo file " << fifoname << std::endl;

	BARRIER(); // signal to consumer that it has created

	// open pipe
	if ((fd = open(fifoname, O_WRONLY)) < 0) {
		perror("open"); exit(1);
	}

	BARRIER(); // wait for consumer to open

	// write to pipe

	str = NULL;
	prod_reall();

	std::cout << "Data written into memory: " << str[0] << std::endl;

	BARRIER(); // signal to consumer that it has written

	std::cin.get();

	cont = true;
	suspend = false;

	// BARRIER();

	std::future<void> out = std::async(std::launch::async, loop);

	// signal(SIGINT, detach);
	while (suspend || prod_update())
		usleep(UPDPER);

	out.wait();

	// terminate();

	std::cout << "rank " << rank  << " finished waiting" << std::endl;

	delete str; // alloc->shm_free(str);

	std::cout << "entering barrier" << std::endl;

	BARRIER(); // wait for consumer to finish reading

	std::cout << "passed barrier" << std::endl;

	// std::cout << "rank " << rank << " with offset " << offset << " alloc: " << ((long) alloc) << std::endl;

	// delete alloc; // for some reason this freezes program
	// close(fd[1]);
	close(fd);

	std::cout << "rank " << rank << " closed pipe" << std::endl;

	BARRIER(); // wait for consumer to detach

	remove(fifoname);
}

// consumer

bool cons_update()
{
	static int cnt = 0;

	// receive from producer
	if (cnt % PRINTPER == 0)
    	std::cout << "receiving from " << fd << std::endl;
	int nbytes = read(fd, str, SIZE);
	if (nbytes < SIZE) {
		printf("read returned %d bytes\n", nbytes);
		cont = false;
		return false;
	}

	// move each entry in array based on bits of cnt
	float sum = 0;
	for (int i = 0; i < ARRSIZE; ++i) {
		sum += str[i]*((i*i+1)&3);
	}
	if (cnt % PRINTPER == 0)
		std::cout << "val: " << str[5] << std::endl;

	++cnt;

	return cont; // whether to continue
}

/*
void cons_reall()
{
	buf->update_key();
	str = (float *) buf->attach();
	std::cout << "attached to new" << std::endl;
	buf->detach(false);
}
*/

void cons_terminate()
{

	std::cout << "rank " << rank << " finished waiting" << std::endl;

	// buf->detach(true);
	delete str;
	str = NULL;

	// std::cout << "rank " << rank << " buf: " << ((long) buf) << std::endl;

	BARRIER(); // signal to producer it has finished reading

	// delete buf;
	close(fd);

	std::cout << "rank " << rank << " deleted buf" << std::endl;
}

void consumer()
{
	// wait for producer to create pipe
	SETFIFONAME();

	std::cout << "waiting for fifo name " << fifoname << std::endl;

	BARRIER();

	// open pipe
	if ((fd = open(fifoname, O_RDONLY)) < 0) {
		perror("open"); exit(1);
	}

	std::cout << "opened fifo name " << fifoname << std::endl;

	str = new float[ARRSIZE];

	// BARRIER(); // wait for producer to initialize memory

	std::cout << "starting consumer with rank " << rank << " of size " << size << std::endl;

	// str = NULL;
	// cons_reall();

	BARRIER(); // signal producer that it has opened

	cont = true;
	suspend = false;

	BARRIER(); // wait for producer to write

	std::future<void> out = std::async(std::launch::async, loop);

	// signal(SIGINT, detach);
	while (suspend || cons_update())
		usleep(UPDPER);

	out.wait();

	cons_terminate();

	BARRIER(); // wait for producer to close file
}



int main(int argc, char *argv[])
{
	MPI_Init(&argc, &argv);

	MPI_Comm_size(MPI_COMM_WORLD, &size);
	MPI_Comm_rank(MPI_COMM_WORLD, &rank);
	
	if (ISPRODUCER)
		producer();
	else
		consumer();
	

	MPI_Finalize();
}
