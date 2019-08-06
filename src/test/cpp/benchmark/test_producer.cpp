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
#define SEND tcp ## _send
#define TERM tcp ## _term

int i, j, k;
size_t x;
float *arr, *ptr;
int id, fd;
const char *fifoname = "/tmp/test.fifo";

// test methods

void heap_send();

void sysv_send();
void mmap_send();

void fifo_init();
void fifo_send();
void fifo_term();

void tcp_init();
void tcp_send();
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
	// initialize arr
	for (int i = 0; i < ARRSIZE; ++i) {
		arr[i] = ((7*i) % 20 - 10) / 5.0;
	}
	std::cout << "Initialized data" << std::endl;

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
			SEND();
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

void heap_send()
{
	ptr = (float *) malloc(x); // acquire pointer
	memcpy(ptr, arr, x); // send data
	free(ptr); // release pointer
}

// system v shm

void sysv_send()
{
	// acquire id
	key_t key = ftok("/tmp", RANK);
	id = shmget(key, x, 0666|IPC_CREAT);
	if (id < 0) { perror("shmget"); exit(1); }

	// acquire pointer
	ptr = (float *) shmat(id, NULL, 0);
	if (ptr == MAP_FAILED) { perror("shmat"); exit(1); }

	// send data
	memcpy(ptr, arr, x);

	// release pointer
	shmdt(ptr);

	// release id
	shmctl(id, IPC_RMID, NULL);
}

// posix shm

void mmap_send()
{
	// acquire id
	fd = shm_open(NAME, O_CREAT | O_EXCL | O_RDWR, 0666);
	if (fd < 0) { perror("shm_open"); exit(1); }
	ftruncate(fd, x);

	// acquire pointer
	ptr = (float *) mmap(NULL, x, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
	if (ptr == MAP_FAILED) { perror("mmap"); exit(1); }
	close(fd);

	// send data
	memcpy(ptr, arr, x);

	// TODO remove after consumer finishes (or for testing, make consumer delete it)

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

	// open pipe
	if ((fd = open(fifoname, O_WRONLY)) < 0) {
		perror("open"); exit(1);
	}
}

void fifo_send()
{
	// send data directly
	write(fd, arr, x);
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
    	close(id);
    	perror("bind"); exit(1);
    }

    // listen
    if (listen(id, 5) != 0) {
    	close(id);
		perror("listen"); exit(1);
	}

	// accept client
	struct sockaddr_in client;
	socklen_t len = sizeof client;
	fd = accept(id, (struct sockaddr *) &client, &len);
	if (fd < 0) {
    	close(id);
		perror("accept"); exit(1);
	}
}

void tcp_send()
{
	// send data directly
	write(fd, arr, x);
}

void tcp_term()
{
	close(id);
}