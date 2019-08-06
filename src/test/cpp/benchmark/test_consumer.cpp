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

// generate sizes in logarithmic scale, in bytes
#define MINSIZE 1024
#define SIZELEN 15
#define SIZE(i) (MINSIZE * (1 << (i)))
#define MAXSIZE SIZE(SIZELEN)
#define ARRSIZE (MAXSIZE/sizeof(float))

#define ITERS 500

#define RANK 12
#define NAME "test"
#define PORT 8080

#define INIT tcp ## _init
#define RECV tcp ## _recv
#define TERM tcp ## _term

int i, j, k;
size_t x;
float *arr, *ptr;
int id, fd;
const char *fifoname = "/tmp/test.fifo";

// test methods

void heap_recv();

void sysv_recv();
void mmap_recv();

void fifo_init();
void fifo_recv();
void fifo_term();

void tcp_init();
void tcp_recv();
void tcp_term();

#define EMPTY() do {} while (0)
#define heap_init EMPTY
#define heap_term EMPTY
#define sysv_init EMPTY
#define sysv_term EMPTY
#define mmap_init EMPTY
#define mmap_term EMPTY

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

#define AVGTIME() ((stop - start) / ITERS)

int main()
{
	// create random data
	std::cout << "Array size: " << ARRSIZE << std::endl;
	arr = new float[ARRSIZE];

	// initialize pipe, tcp etc.
	INIT();
	std::cout << "Initialized resource" << std::endl;

	// std::cout << "Start? ";
	// std::cin.get();

	// possibly fork, have one process for producer and another for consumer, make them synchronized

	for (i = 0, x = SIZE(i); i < SIZELEN; i++, x = SIZE(i)) {
		std::cout << "testing with size " << x << " bytes" << std::endl;

		// record start time
		START();

		for (j = 0; j < ITERS; j++) {
			RECV();
		}

		// record end time
		STOP();

		// store (end time - start time) / ITERS
		std::cout << "size: " << x << "\ttime: " << AVGTIME() << " us" << std::endl;
	}

	// delete fifo etc.
	TERM();
	std::cout << "Deleted resource" << std::endl;

	delete[] arr;
	std::cout << "Deleted data" << std::endl;
}

// local memory, as control

void heap_recv()
{
	ptr = (float *) malloc(x); // acquire pointer
	memcpy(arr, ptr, x); // read data
	free(ptr); // release pointer
}

// system v shm

void sysv_recv()
{
	// acquire id
	key_t key = ftok("/tmp", RANK);
	id = shmget(key, x, 0666|IPC_CREAT);
	if (id < 0) { perror("shmget"); exit(1); }

	// acquire pointer
	ptr = (float *) shmat(id, NULL, 0);
	if (ptr == MAP_FAILED) { perror("shmat"); exit(1); }

	// receive data
	memcpy(arr, ptr, x);

	// release pointer
	shmdt(ptr);

	// release id
	shmctl(id, IPC_RMID, NULL);
}

// posix shm

void mmap_recv()
{
	// acquire id
	fd = shm_open(NAME, O_RDONLY, 0666);
	if (fd < 0) { perror("shm_open"); exit(1); }
	ftruncate(fd, x);

	ptr = (float *) mmap(NULL, x, PROT_READ, MAP_SHARED, fd, 0);
	if (ptr == MAP_FAILED) { perror("mmap"); exit(1); }
	close(fd);

	memcpy(arr, ptr, x);

	munmap(ptr, x);
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
	if (fd < 0) { perror("socket"); exit(1); }

	// connect
	struct sockaddr_in servaddr;
	bzero(&servaddr, sizeof(servaddr));
	servaddr.sin_family = AF_INET;
	servaddr.sin_addr.s_addr = inet_addr("127.0.0.1");
	servaddr.sin_port = htons(PORT);
    if (connect(fd, (struct sockaddr *) &servaddr, sizeof(servaddr)) != 0) {
    	perror("connect"); exit(1);
    }
}

void tcp_recv()
{
	// send data directly
	read(fd, arr, x);
}

void tcp_term()
{
	close(fd);
}