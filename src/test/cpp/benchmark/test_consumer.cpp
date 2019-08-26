#include "test_params.hpp"

// semaphore communication

#define ISPROD false
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
// global variables

int i, j, k;
size_t x = MAXSIZE;
float *arr, *ptr;
int id, fd;
const char *fifoname = "/tmp/test.fifo";
bool looping = false;

SemManager sem("/tmp", RANK, VERBOSE, ISPROD); // use only 0th key, first semaphore for producer to wait, second for consumer to wait

int main()
{
	// create random data
	std::cout << "Array size: " << ARRSIZE << std::endl;
	arr = new float[ARRSIZE];

	// signal producer you are ready
	WAIT();
	SIGNAL();
	std::cout << "Signaled producer" << std::endl;

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

	looping = true;
	for (i = 0, x = SIZE(i); i < SIZELEN; i++, x = SIZE(i)) {
		std::cout << "testing with size " << x << " bytes" << std::endl;

		#if !INITONCE
		WAIT();
		INIT();
		SIGNAL();
		#endif

		// record start time
		START();

		for (j = 0; j < ITERS; j++) {
			if (VERBOSE) printf("receiving\n");
			RECV();   // receive data
			if (VERBOSE) printf("received\n");
			SIGNAL(); // signal producer that you received data
		}

		// record end time
		STOP();

		#if !INITONCE
		WAIT();
		TERM();
		SIGNAL();
		#endif

		// store (end time - start time) / ITERS
		std::cout << "size: " << x << "\ttime: " << AVGTIME() << " us" << std::endl;
	}
	looping = false;

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

	size_t offset = 0, remaining = x, res;
	size_t limit = PARTITION ? SOCKSIZE : x;
	while (remaining) {
		WAIT(); // wait for new data (like read waits for write)
		res = MIN(limit, remaining);
		// memcpy(arr+offset/sizeof(float), ptr+offset/sizeof(float), res);
		offset += res;
		remaining -= res;

		if (remaining) {
			SIGNAL(); // alert producer it can write again (like read empties buffer)
		}
	}
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

	if (looping ^ INITONCE) {
		ptr = (float *) malloc(x); // acquire pointer
	}
}

void heap_recv()
{
	// memcpy(arr, ptr, x); // read data

	size_t offset = 0, remaining = x, res;
	size_t limit = PARTITION ? SOCKSIZE : x;
	while (remaining) {
		WAIT(); // wait for new data (like read waits for write)
		memcpy(arr+offset/sizeof(float), ptr+offset/sizeof(float), res = MIN(limit, remaining));
		offset += res;
		remaining -= res;

		if (remaining) {
			SIGNAL(); // alert producer it can write again (like read empties buffer)
		}
	}
}

void heap_term()
{
	if (looping ^ INITONCE) {
		free(ptr); // release pointer
	}
}

// system v shm

void sysv_init()
{
	if (looping ^ INITONCE) {
		// acquire id
		key_t key = ftok("/tmp", RANK);
		id = shmget(key, x, 0666|IPC_CREAT);
		if (id < 0) { perror("shmget"); exit(1); }

		// acquire pointer
		ptr = (float *) shmat(id, NULL, 0);
		if (ptr == MAP_FAILED) { perror("shmat"); exit(1); }
	}
}

void sysv_recv()
{
	// receive data
	// memcpy(arr, ptr, x);

	// for a fair comparison, loop here as well as in fifo_recv
	size_t offset = 0, remaining = x, res;
	size_t limit = PARTITION ? SOCKSIZE : x;
	while (remaining) {
		WAIT(); // wait for new data (like read waits for write)
		memcpy(arr+offset/sizeof(float), ptr+offset/sizeof(float), res = MIN(limit, remaining));
		offset += res;
		remaining -= res;

		if (remaining) {
			SIGNAL(); // alert producer it can write again (like read empties buffer)
		}
	}
}

void sysv_term()
{
	if (looping ^ INITONCE) {
		// WAIT();   // wait for producer to finish

		// release pointer
		shmdt(ptr);

		// SIGNAL(); // signal producer to remove shm
	}
}

// posix shm

void mmap_init()
{
	if (looping ^ INITONCE) {
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
}

void mmap_recv()
{
	// memcpy(arr, ptr, x);

    size_t offset = 0, remaining = x, res;
	size_t limit = PARTITION ? SOCKSIZE : x;
	while (remaining) {
		WAIT(); // wait for new data (like read waits for write)
		memcpy(arr+offset/sizeof(float), ptr+offset/sizeof(float), res = MIN(limit, remaining));
		offset += res;
		remaining -= res;

		if (remaining) {
			SIGNAL(); // alert producer it can write again (like read empties buffer)
		}
	}
}

void mmap_term()
{
	if (looping ^ INITONCE) {
		// WAIT(); // wait for producer to finish

		// release pointer
		munmap(ptr, x);

		// SIGNAL(); // signal producer that you have finished
	}
}

// named pipes

void fifo_init()
{
	if (looping) return;

	SIGNAL(); // signal producer to open fifo

	// potentially wait for file to be created
	if (VERBOSE) printf("opening fifo\n");

	// open pipe
	if ((fd = open(fifoname, O_RDONLY)) < 0) {
		perror("open"); exit(1);
	}

	if (VERBOSE) printf("opened fifo\n");

	WAIT(); // wait until producer opens
}

void fifo_recv()
{
	/*
	// receive data directly
	int size = read(fd, arr, x);
	printf("%d\n", size);
	*/

	// loop until x bytes read
	size_t offset = 0, remaining = x, res;
	while (remaining) {
		res = read(fd, arr+offset/sizeof(float), MIN(PIPESIZE, remaining));
		// if (errno == EAGAIN)
		// 	res = 0; // try again
		if (res == -1) {
			perror("read"); exit(1);
		}
		offset += res;
		remaining -= res;
		// if (VERBOSE && res)
		// 	printf("read %lu bytes, total %lu, remaining %lu\n", res, offset, remaining);
	}
}

void fifo_term()
{
	if (looping) return;
	close(fd);
}

// tcp/ip

void tcp_init()
{
	if (looping) return;
	SIGNAL(); // signal producer to create socket

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

    WAIT(); // wait until producer opens
}

void tcp_recv()
{
	/*
	// send data directly
	if (read(fd, arr, x) <= 0) {
		perror("read");
		close(fd); exit(1);
	}
	*/

	// loop until x bytes read
	size_t offset = 0, remaining = x, res;
	while (remaining) {
		res = read(fd, arr+offset/sizeof(float), MIN(SOCKSIZE, remaining));
		// if (errno == EAGAIN)
		// 	res = 0; // try again
		if (res == -1) {
			perror("read"); exit(1);
		}
		offset += res;
		remaining -= res;
		// if (VERBOSE && res)
		// 	printf("read %lu bytes, total %lu, remaining %lu\n", res, offset, remaining);
	}
}

void tcp_term()
{
	if (looping) return;
	close(fd);
}