// Simple producer for named pipes

// Send and receive data through named pipes

#define _OPEN_SYS
#include <iostream>
#include <future>
#include <fcntl.h>
#include <sys/sem.h>
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
#define COPYSTR false
#define NOCONSUMER false
#define SHMRANK (rank+3)
#define SYNCHRONIZE true

int rank, size;
// int fd[2];
int fd;

bool cont, suspend;
float *str = NULL;
char fifoname[] = "/tmp/temp0.fifo";

#define SETFIFONAME() do { fifoname[9] += rank; } while (0)

// synchronize initially with consumer using semaphores

int semid;
union semun sem_attr;
struct sembuf semops[2];

void setsem() // set to 0
{
	sem_attr.val = 0;
	semctl(semid, 0, SETVAL, sem_attr);
}

void waitsem() // decrement by 1
{
	semops[0].sem_num = 0;
	semops[0].sem_op  = -1;
	semops[0].sem_flg = 0;
	if (semop(semid, semops, 1) == -1) {
		perror("semop"); std::exit(1);
	}
}

void signalsem() // increment by 1, wait for 0
{
	semops[0].sem_num = 0;
	semops[0].sem_op  = 1;
	semops[0].sem_flg = 0;
	semops[1].sem_num = 0;
	semops[1].sem_op  = 0;
	semops[1].sem_flg = 0;
	if (semop(semid, semops, 2) == -1) {
		perror("semop"); std::exit(1);
	}
}

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

	signalsem(); // signal corresponding consumer to finish

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
	for (int i = 0; i < ARRSIZE; ++i) {
		str[i] = ((7*i) % 20 - 10) / 5.0;
	}

	// send to consumer
	std::cout << "sending to consumer" << std::endl;
	// write(fd[1], str, SIZE);
	write(fd, str, SIZE);
}

bool prod_update()
{
	static int cnt = 0;

	// move each entry in array based on bits of cnt
	for (int i = 0; i < ARRSIZE; ++i) {
		str[i] += 0.02 * ((cnt & (1 << (i & ((1 << 4) - 1)))) ? 1 : -1);
	}

	++cnt;

	// send to consumer
	if (cnt % PRINTPER == 0)
		std::cout << "sending to " << fd << std::endl;
	// write(fd[1], str, SIZE);
	write(fd, str, SIZE);

	return cont; // whether to continue
}

int main(int argc, char *argv[])
{
	MPI_Init(&argc, &argv);

	MPI_Comm_size(MPI_COMM_WORLD, &size);
	MPI_Comm_rank(MPI_COMM_WORLD, &rank);

	semid = semget(SHMRANK, 1, 0666|IPC_CREAT);
	setsem();

	std::cout << "starting producer with rank " << rank << " of size " << size << std::endl;

	// create pipe
	SETFIFONAME();
	if (mkfifo(fifoname, 0666)) {
		perror("mkfifo"); exit(1);
	}

	std::cout << "made fifo file " << fifoname << std::endl;

	waitsem(); // signal that producer has created

	// open pipe
	if ((fd = open(fifoname, O_WRONLY)) < 0) {
		perror("open"); exit(1);
	}

	waitsem(); // wait for consumer to open

	// write to pipe

	str = new float[ARRSIZE];
	prod_initstr();

	waitsem(); // signal that producer has written

	std::cout << "Data written into memory: " << str[0] << std::endl;

	// std::cin.get();

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

	waitsem(); // wait for consumer to finish reading

	close(fd);

	std::cout << "rank " << rank << " closed pipe" << std::endl;

	waitsem(); // wait for consumer to detach

	remove(fifoname);
	semctl(semid, 0, IPC_RMID);

	MPI_Finalize();
}