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

#define SETFIFONAME() do { fifoname[9] += rank; } while (0)

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
	waitsem(); // wait for producer to finish

	std::cout << "Exiting rank " << rank << std::endl;
	std::cout << "suspend: " << suspend << "\tcont: " << cont << std::endl;
}

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

void cons_terminate()
{

	std::cout << "rank " << rank << " finished waiting" << std::endl;

	// buf->detach(true);
	delete str;
	str = NULL;

	// std::cout << "rank " << rank << " buf: " << ((long) buf) << std::endl;
	signalsem(); // signal to producer that it finished reading

	// delete buf;
	close(fd);

	std::cout << "rank " << rank << " deleted buf" << std::endl;
}

// function to increment semaphore and wait for it to become 0

int main(int argc, char *argv[])
{
	MPI_Init(&argc, &argv);

	MPI_Comm_size(MPI_COMM_WORLD, &size);
	MPI_Comm_rank(MPI_COMM_WORLD, &rank);

	semid = semget(SHMRANK, 1, 0666|IPC_CREAT);

	// wait for producer to create pipe
	SETFIFONAME();

	std::cout << "waiting for " << fifoname << std::endl;

	waitsem(); // wait for producer to create

	// open pipe
	if ((fd = open(fifoname, O_RDONLY)) < 0) {
		perror("open"); exit(1);
	}

	signalsem(); // signal that it has opened

	std::cout << "opened fifo name " << fifoname << std::endl;

	str = new float[ARRSIZE];

	// BARRIER(); // wait for producer to initialize memory

	std::cout << "starting consumer with rank " << rank << " of size " << size << std::endl;

	// str = NULL;
	// cons_reall();

	cont = true;
	suspend = false;

	waitsem(); // wait for producer to write

	std::future<void> out = std::async(std::launch::async, loop);

	// signal(SIGINT, detach);
	while (suspend || cons_update())
		usleep(UPDPER);

	out.wait();

	cons_terminate();

	signalsem(); // signal to producer that it detached

	MPI_Finalize();
}