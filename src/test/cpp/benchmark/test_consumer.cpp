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
#include <arpa/inet.h>

#include "../SemManager.hpp"

// generate sizes in logarithmic scale, in bytes
#define MINSIZE 1024
#define SIZELEN 12
#define SIZE(i) (MINSIZE * (1 << (i)))
#define MAXSIZE SIZE(SIZELEN)
#define ARRSIZE (MAXSIZE/sizeof(float))

#define ITERS 500

#define RANK 12
#define NAME "test"
#define PORT 8080
#define VERBOSE false

#define INIT sem ## _init
#define RECV sem ## _recv
#define TERM sem ## _term

// semaphore communication

#define ISPROD false
#define PROSEM 1
#define CONSEM 0
#define OWNSEM (ISPROD ? PROSEM : CONSEM)
#define OPPSEM (ISPROD ? CONSEM : PROSEM)

// wait for opposite semaphore to be decremented
#define WAIT() do { 		\
	sem.incr(0, OPPSEM);	\
	sem.wait(0, OPPSEM);	\
} while (0)

// decrement own semaphore
#define SIGNAL() do { 		\
	sem.decr(0, OWNSEM);	\
} while (0)

// test methods

void sem_init();
void sem_recv();
void sem_term();

void heap_init();
void heap_recv();
void heap_term();

void sysv_init();
void sysv_recv();
void sysv_term();

void mmap_init();
void mmap_recv();
void mmap_term();

void fifo_init();
void fifo_recv();
void fifo_term();

void tcp_init();
void tcp_recv();
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
	// create random data
	std::cout << "Array size: " << ARRSIZE << std::endl;
	arr = new float[ARRSIZE];

	// initialize channel
	START();
	WAIT();   // wait for producer to initialize channel
	INIT();   // attach to channel
	SIGNAL(); // signal producer that you have attached
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
			WAIT();   // wait for producer to send data
			RECV();   // receive data
			SIGNAL(); // signal producer that you received data
		}

		// record end time
		STOP();

		// store (end time - start time) / ITERS
		std::cout << "size: " << x << "\ttime: " << AVGTIME() << " us" << std::endl;
	}

	// delete channel
	START();
	WAIT();   // wait for producer to finish (may not be necessary)
	TERM();   // detach from channel
	SIGNAL(); // signal producer that you have detached
	STOP();
	std::cout << "Deleted resource" << std::endl;
	std::cout << "term time: " << TOTTIME() << " us" << std::endl;

	delete[] arr;
	std::cout << "Deleted data" << std::endl;
}

// semaphore I/O

void sem_init()
{
	// WAIT();   // wait for producer to join
	// attach to IPC etc.
	// SIGNAL(); // signal producer that you have joined
}

void sem_recv()
{
	// WAIT();   // wait for producer to increment 1st semaphore
	// receive data etc.
	// SIGNAL(); // signal producer to decrement 0th semaphore
}

void sem_term()
{
	// WAIT();   // wait for producer to finish
	// detach etc.
	// SIGNAL(); // signal producer to remove IPC
}

// local memory, as control

void heap_init()
{
	ptr = (float *) malloc(x); // acquire pointer
}

void heap_recv()
{
	ptr = (float *) malloc(x); // acquire pointer
	memcpy(arr, ptr, x); // read data
	free(ptr); // release pointer
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


}

void sysv_recv()
{
	// wait for producer to write
	// WAIT();

	// receive data
	memcpy(arr, ptr, x);

	// signal producer that you have received
	// SIGNAL();
}

void sysv_term()
{
	// WAIT();   // wait for producer to finish

	// release pointer
	shmdt(ptr);

	// SIGNAL(); // signal producer to remove shm
}

// posix shm

void mmap_init()
{
	// WAIT(); // wait for consumer to create shared memory

	// acquire id
	fd = shm_open(NAME, O_RDONLY, 0666);
	if (fd < 0) { perror("shm_open"); exit(1); }
	ftruncate(fd, x);

	ptr = (float *) mmap(NULL, x, PROT_READ, MAP_SHARED, fd, 0);
	if (ptr == MAP_FAILED) { perror("mmap"); exit(1); }
	close(fd);

	// SIGNAL(); // signal producer
}

void mmap_recv()
{
	memcpy(arr, ptr, x);
}

void mmap_term()
{
	// WAIT(); // wait for producer to finish

	// release pointer
	munmap(ptr, x);

	// SIGNAL(); // signal producer that you have finished
}

// named pipes

void fifo_init()
{
	// potentially wait for file to be created

	// open pipe
	if ((fd = open(fifoname, O_RDONLY)) < 0) {
		perror("open"); exit(1);
	}
}

void fifo_recv()
{
	// receive data directly
	read(fd, arr, x);
}

void fifo_term()
{
	close(fd);
}

// tcp/ip

void tcp_init()
{
	// create socket
	fd = socket(AF_INET, SOCK_STREAM, 0);
	if (fd < 0) {
		perror("socket");
    	close(id); exit(1);
    }

	// connect
	struct sockaddr_in servaddr;
	bzero(&servaddr, sizeof(servaddr));
	servaddr.sin_family = AF_INET;
	servaddr.sin_addr.s_addr = inet_addr("127.0.0.1");
	servaddr.sin_port = htons(PORT);
    if (connect(fd, (struct sockaddr *) &servaddr, sizeof(servaddr)) != 0) {
    	perror("connect");
    	close(fd); exit(1);
    }
}

void tcp_recv()
{
	// send data directly
	if (read(fd, arr, x) <= 0) {
		perror("read");
		close(fd); exit(1);
	}
}

void tcp_term()
{
	close(fd);
}