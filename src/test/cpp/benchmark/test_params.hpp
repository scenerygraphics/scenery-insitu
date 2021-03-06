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

#include "SemManager.hpp"

// generate sizes in logarithmic scale, in bytes
#define MINSIZE 1024
#define SIZELEN 21 // 22
#define SIZE(i) ((size_t) MINSIZE * (1 << (i)))
#define MAXSIZE SIZE(SIZELEN-1)
#define ARRSIZE (MAXSIZE/sizeof(float))

#define PIPESIZE 8192 // maximum pipe size
#define SOCKSIZE 524288
#define MIN(x,y) ((x) < (y) ? (x) : (y))

#define ITERS 5000 // 5000
#define COMPINT 10 // call compute every 10th iteration
#define MATSIZ 100 // matrix size for computation

#define RANK 11
#define NAME "/test.mmap"
#define PORT 8080
#define VERBOSE false
#define COMPUTE true
#define LONE false // whether to run producer alone
#define PARTITION true // whether to break up data sent into smaller pieces (for sysv and mmap, irrelevant if BUSYWAIT is true)
#define INITONCE true // whether to initialize memory in the beginning or at each iteration
#define BUSYWAIT true // whether to wait for shared memory updates through semaphore calls or loops

#define INIT sem ## _init // create resource in beginning before other side joins
#define SEND sem ## _send // send data
#define RECV sem ## _recv // receive data
#define TERM sem ## _term // delete resource

// test methods

void sem_init();
void sem_send();
void sem_recv();
void sem_term();

void heap_init();
void heap_send();
void heap_recv();
void heap_term();

void sysv_init();
void sysv_send();
void sysv_recv();
void sysv_term();

void mmap_init();
void mmap_send();
void mmap_recv();
void mmap_term();

void fifo_init();
void fifo_send();
void fifo_recv();
void fifo_term();

void tcp_init();
void tcp_send();
void tcp_recv();
void tcp_term();

#define EMPTY() do {} while (0)

void compute();

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