#include <iostream>
#include <unistd.h>
#include <fcntl.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <inttypes.h>
#include <sys/ipc.h>
#include <sys/shm.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <netdb.h>
#include <netinet/in.h>

#include "../SemManager.hpp"

// generate sizes in logarithmic scale, in bytes
#define MINSIZE 1024
#define SIZELEN 12
#define SIZE(i) (MINSIZE * (1 << (i)))
#define MAXSIZE SIZE(SIZELEN)
#define ARRSIZE (MAXSIZE/sizeof(float))

#define ITERS 1

#define RANK 12
#define NAME "/tmp/mmap_test"
#define PORT 8080
#define VERBOSE true

#define INIT fifo ## _init
#define SEND fifo ## _send
#define TERM fifo ## _term

// semaphore communication

#define ISPROD true
#define PROSEM 1
#define CONSEM 0
#define OWNSEM (ISPROD ? PROSEM : CONSEM)
#define OPPSEM (ISPROD ? CONSEM : PROSEM)

// wait for opposite semaphore to be decremented
#define WAIT() do { 		\
	sem.incr(0, OPPSEM);	\
	if (VERBOSE) std::cout << "waiting for " << OPPSEM << std::endl; \
	sem.wait(0, OPPSEM);	\
	if (VERBOSE) std::cout << "waited for " << OPPSEM << std::endl; \
} while (0)

// decrement own semaphore
#define SIGNAL() do { 		\
	if (VERBOSE) std::cout << "decrementing " << OWNSEM << std::endl; \
	sem.decr(0, OWNSEM);	\
	if (VERBOSE) std::cout << "decremented " << OWNSEM << std::endl; \
} while (0)

// test methods

void sem_init();
void sem_send();
void sem_term();

void heap_init();
void heap_send();
void heap_term();

void sysv_init();
void sysv_send();
void sysv_term();

void mmap_init();
void mmap_send();
void mmap_term();

void fifo_init();
void fifo_send();
void fifo_term();

void tcp_init();
void tcp_send();
void tcp_term();

#define EMPTY() do {} while (0)

// time measurement in milliseconds

long start, stop;
struct timespec tspec;

#define GETTIME(var) do { 									\
	if (clock_gettime( CLOCK_REALTIME, &tspec) < 0) {		\
		perror("clock_gettime"); exit(1);					\
	}														\
	var = tspec.tv_sec * 1000000L + tspec.tv_nsec / 1000;	\
} while (0)

#define START() GETTIME(start)
#define STOP()  GETTIME(stop)

#define TOTTIME() (stop - start)
#define AVGTIME() (TOTTIME() / ITERS)

// global variables

int i, j, k;
size_t x;
float *arr, *ptr;
int id, fd;
const char *fifoname = "/tmp/test.fifo";

SemManager sem("/tmp", RANK, VERBOSE, ISPROD); // use only 0th key, first semaphore for producer to wait, second for consumer to wait

int main()
{
	// reset semaphores
	sem.set(0, 0, 0);
	sem.set(0, 1, 0);

	// create random data
	std::cout << "Array size: " << ARRSIZE << std::endl;
	arr = new float[ARRSIZE];
	// initialize arr
	for (int i = 0; i < ARRSIZE; ++i) {
		arr[i] = ((7*i) % 20 - 10) / 5.0;
	}
	std::cout << "Initialized data" << std::endl;

	// initialize channel
	START();

	INIT();   // create channel
	SIGNAL(); // signal consumer that channel created
	WAIT();   // wait for consumer to attach

	STOP();
	std::cout << "Initialized resource" << std::endl;
	std::cout << "init time: " << TOTTIME() << " us" << std::endl;

	// std::cout << "Start? ";
	// std::cin.get();

	// possibly fork, have one process for producer and another for consumer, make them synchronized

	for (i = 0, x = SIZE(i); i < SIZELEN; i++, x = SIZE(i)) {
		std::cout << "testing with size " << x << " bytes" << std::endl;

		// record start time
		START();

		for (j = 0; j < ITERS; j++) {
			SEND();   // send message
			SIGNAL(); // alert consumer to receive message
			WAIT();   // wait till consumer processes message
		}

		// record end time
		STOP();

		// store (end time - start time) / ITERS
		std::cout << "size: " << x << "\ttime: " << AVGTIME() << " us" << std::endl;
	}

	// delete channel
	START();

	SIGNAL(); // signal consumer you're ready to terminate
	WAIT();   // wait for consumer to detach
	TERM();   // delete resource

	STOP();
	std::cout << "Deleted resource" << std::endl;
	std::cout << "term time: " << TOTTIME() << " us" << std::endl;

	delete[] arr;
	std::cout << "Deleted data" << std::endl;
}

// semaphore I/O

void sem_init()
{

	// SIGNAL(); // signal consumer that you have joined
	// WAIT();   // wait for consumer to join
}

void sem_send()
{
	// SIGNAL(); // signal consumer waiting to decrement 1st semaphore
	// transfer memory etc.
	// WAIT(); // wait for consumer to increment 0th semaphore
	// sem.wait(0, 0); // for protection, to move in tandem with consumer
}

void sem_term()
{
	// SIGNAL(); // signal consumer that you have finished
	// WAIT(); // wait for consumer to finish
	// destroy memory etc.
}

// local memory, as control

void heap_init()
{
	ptr = (float *) malloc(x); // acquire pointer
}

void heap_send()
{
	memcpy(ptr, arr, x); // send data
}

void heap_term()
{
	free(ptr); // release pointer
}

// system v shm

void sysv_init()
{
	// acquire id
	key_t key = ftok("/tmp", RANK);
	id = shmget(key, MAXSIZE, 0666|IPC_CREAT);
	if (id < 0) { perror("shmget"); exit(1); }

	// acquire pointer
	ptr = (float *) shmat(id, NULL, 0);
	if (ptr == MAP_FAILED) { perror("shmat"); exit(1); }

	// possibly alert consumer
}

void sysv_send()
{
	// send data
	memcpy(ptr, arr, x);
}

void sysv_term()
{
	// possibly wait for consumer to terminate

	// release pointer
	shmdt(ptr);

	// release id
	shmctl(id, IPC_RMID, NULL);
}

// posix shm

void mmap_init()
{
	// acquire id
	fd = shm_open(NAME, O_CREAT | O_EXCL | O_RDWR, 0666);
	if (fd < 0) { perror("shm_open"); exit(1); }
	ftruncate(fd, MAXSIZE);

	// acquire pointer
	ptr = (float *) mmap(NULL, MAXSIZE, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
	if (ptr == MAP_FAILED) { perror("mmap"); exit(1); }
	close(fd);

	// signal consumer
}

void mmap_send()
{

	// send data
	memcpy(ptr, arr, x);
}

void mmap_term()
{
	// wait for consumer

	// release pointer
	munmap(ptr, x);

	// release id
	if (shm_unlink(NAME) < 0) {
		perror("unlink"); exit(1);
	}
}

// named pipes

void fifo_init()
{
	if (mkfifo(fifoname, 0666) != 0) {
		perror("mkfifo"); exit(1);
	}

	if (VERBOSE) std::cout << "created fifo" << std::endl;

	// open pipe
	if ((fd = open(fifoname, O_RDWR)) < 0) {
		perror("open"); exit(1);
	}

	if (VERBOSE) std::cout << "opened fifo" << std::endl;
}

void fifo_send()
{
	// send data directly
	if (write(fd, arr, x) == -1) {
		perror("write"); exit(1);
	}
}

void fifo_term()
{
	close(fd);
	remove(fifoname);
}

// tcp/ip

void tcp_init()
{
	// create socket
	id = socket(AF_INET, SOCK_STREAM, 0);
	if (id < 0) { perror("socket"); exit(1);	}

	// bind
	struct sockaddr_in servaddr;
    bzero(&servaddr, sizeof(servaddr));
    servaddr.sin_family = AF_INET;
    servaddr.sin_addr.s_addr = htonl(INADDR_ANY);
    servaddr.sin_port = htons(PORT);
    if (bind(id, (struct sockaddr *) &servaddr, sizeof servaddr) != 0) {
    	perror("bind");
    	close(id); exit(1);
    }

    // listen
    if (listen(id, 5) != 0) {
		perror("listen");
    	close(id); exit(1);
	}

	// accept client
	struct sockaddr_in client;
	socklen_t len = sizeof client;
	fd = accept(id, (struct sockaddr *) &client, &len);
	if (fd < 0) {
		perror("accept");
    	close(id); exit(1);
	}
}

void tcp_send()
{
	// send data directly
	if (write(fd, arr, x) == -1) {
		perror("write");
		close(id); exit(1);
	}
}

void tcp_term()
{
	close(id);
}