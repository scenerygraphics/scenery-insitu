#include "test_params.hpp"

// semaphore communication

#define ISPROD true
#define PROSEM 1
#define CONSEM 0
#define OWNSEM (ISPROD ? PROSEM : CONSEM)
#define OPPSEM (ISPROD ? CONSEM : PROSEM)

#if LONE

#define WAIT() EMPTY()
#define SIGNAL() EMPTY()

#else

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

#endif

// global variables

int i, j, k;
size_t x = MAXSIZE; // so that initializing before loop would allocate at once
float *arr, *ptr;
int id, fd;
const char *fifoname = "/tmp/test.fifo";
bool looping = false; // to initialize shared memory each iteration but sockets etc. in the beginning

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

	// wait for consumer to start
	std::cout << "Waiting for consumer" << std::endl;
	SIGNAL(); // signal you are open
	WAIT();   // wait for consumer to attach

	std::cout << "Initializing resource" << std::endl;

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

	looping = true;
	for (i = 0, x = SIZE(i); i < SIZELEN; i++, x = SIZE(i)) {
		std::cout << "testing with size " << x << " bytes" << std::endl;

		#if !INITONCE
		// initialize within loop
		INIT();
		SIGNAL();
		WAIT();
		#endif

		// record start time
		START();

		for (j = 0; j < ITERS; j++) {
			// if (VERBOSE) printf("sending\n");
			SEND();   // send message
			// if (VERBOSE) printf("sent\n");
			WAIT();   // wait till consumer processes message

			#if COMPUTE
			if (j % COMPINT == 0)
				compute(); // perform compute-intensive operation after each iteration
			#endif
		}

		// record end time
		STOP();

		#if !INITONCE
		// terminate within loop
		SIGNAL();
		WAIT();
		TERM();
		#endif

		// store (end time - start time) / ITERS
		std::cout << "size: " << x << "\ttime: " << AVGTIME() << " us" << std::endl;
	}
	looping = false;

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

	size_t offset = 0, remaining = x, res;
	size_t limit = PARTITION ? SOCKSIZE : x;
	while (remaining) {
		res = MIN(limit, remaining);
		// memcpy(ptr+offset/sizeof(float), arr+offset/sizeof(float), res);
		offset += res;
		remaining -= res;

		SIGNAL(); // alert consumer for new data (like write wakes up read call)
		if (remaining) {
			WAIT(); // wait for consumer to clean up (like write waits for space in buffer)
		}
	}
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
	if (looping ^ INITONCE) // if INITONCE, initialize outside loop, otherwise initialize within loop
		ptr = (float *) malloc(x); // acquire pointer
}

void heap_send()
{
	// memcpy(ptr, arr, x); // send data
	size_t offset = 0, remaining = x, res;
	size_t limit = PARTITION ? SOCKSIZE : x;
	while (remaining) {
		memcpy(ptr+offset/sizeof(float), arr+offset/sizeof(float), res = MIN(limit, remaining));
		offset += res;
		remaining -= res;

		SIGNAL(); // alert consumer for new data (like write wakes up read call)
		if (remaining) {
			WAIT(); // wait for consumer to clean up (like write waits for space in buffer)
		}
	}
}

void heap_term()
{
	if (looping ^ INITONCE)
		free(ptr); // release pointer
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

		// possibly alert consumer
	}
}

void sysv_send()
{
	// send data
	// memcpy(ptr, arr, x);

	// for a fair comparison, loop here as well as in fifo_send
	size_t offset = 0, remaining = x, res;
	size_t limit = PARTITION ? SOCKSIZE : x;
	while (remaining) {
		memcpy(ptr+offset/sizeof(float), arr+offset/sizeof(float), res = MIN(limit, remaining));
		offset += res;
		remaining -= res;

		SIGNAL(); // alert consumer for new data (like write wakes up read call)
		if (remaining) {
			WAIT(); // wait for consumer to clean up (like write waits for space in buffer)
		}
	}
}

void sysv_term()
{
	if (looping ^ INITONCE) {
		// possibly wait for consumer to terminate

		// release pointer
		shmdt(ptr);

		// release id
		shmctl(id, IPC_RMID, NULL);
	}
}

// posix shm

void mmap_init()
{
	if (looping ^ INITONCE) {
		// acquire id
		fd = shm_open(NAME, O_CREAT | O_RDWR, 0666);
		if (fd < 0) { perror("shm_open"); exit(1); }
		ftruncate(fd, MAXSIZE);

		// acquire pointer
		ptr = (float *) mmap(NULL, x, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
		if (ptr == MAP_FAILED) { perror("mmap"); exit(1); }
		close(fd);
	}
}

void mmap_send()
{
	// send data
	// memcpy(ptr, arr, x);

	// for a fair comparison, loop here as well as in fifo_send
	size_t offset = 0, remaining = x, res;
	size_t limit = PARTITION ? SOCKSIZE : x;
	while (remaining) {
		memcpy(ptr+offset/sizeof(float), arr+offset/sizeof(float), res = MIN(limit, remaining));
		offset += res;
		remaining -= res;

		SIGNAL(); // alert consumer for new data (like write wakes up read call)
		if (remaining) {
			WAIT(); // wait for consumer to clean up (like write waits for space in buffer)
		}
	}
}

void mmap_term()
{
	if (looping ^ INITONCE) {
		// wait for consumer

		// release pointer
		munmap(ptr, x);

		// release id
		if (shm_unlink(NAME) < 0) {
			perror("unlink"); exit(1);
		}
	}
}

// named pipes

void fifo_init()
{
	if (looping) return;


	// create fifo

	remove(fifoname);
	if (mkfifo(fifoname, 0666) != 0) {
		perror("mkfifo"); exit(1);
	}

	if (VERBOSE) std::cout << "created fifo" << std::endl;


	// signal consumer to open, wait until it opens
	SIGNAL();
	WAIT();

	// open pipe
	if ((fd = open(fifoname, O_WRONLY)) < 0) {
		perror("open"); exit(1);
	}
	// fcntl(fd, F_SETPIPE_SZ, MAXSIZE);

	if (VERBOSE) std::cout << "opened fifo" << std::endl;
}

void fifo_send()
{
	// for large data, write either blocks or reads only 8192 at a time
	// loop until x bytes read
	size_t offset = 0, remaining = x, res;
	while (remaining) {
		res = write(fd, arr+offset/sizeof(float), MIN(PIPESIZE, remaining));
		if (res == -1) {
			perror("write"); exit(1);
		}
		offset += res;
		remaining -= res;
	}
}

void fifo_term()
{
	if (looping) return;

	close(fd);
	remove(fifoname);
}

// tcp/ip

void tcp_init()
{
	if (looping) return;

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

	// signal consumer to open, wait until it opens
	SIGNAL();
	WAIT();

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
	/*
	// send data directly
	if (write(fd, arr, x) == -1) {
		perror("write");
		close(id); exit(1);
	}
	*/
	size_t offset = 0, remaining = x, res;
	while (remaining) {
		res = write(fd, arr+offset/sizeof(float), MIN(SOCKSIZE, remaining));
		if (res == -1) {
			perror("write"); exit(1);
		}
		offset += res;
		remaining -= res;
	}
}

void tcp_term()
{
	if (looping) return;

	close(id);
}

// compute

#define MATSIZ 100

// compute-intensive matrix multiplication
void compute()
{
#if COMPUTE

	static float **a = new float*[MATSIZ],
		  		 **b = new float*[MATSIZ],
		  		 **c = new float*[MATSIZ];
	static bool initialized = false;

	if (!initialized) {
		for (int i = 0; i < MATSIZ; ++i) {
			a[i] = new float[MATSIZ];
			b[i] = new float[MATSIZ];
			c[i] = new float[MATSIZ];
		}

		// initialize matrices arbitrarily
		float val = 0;
		for (int i = 0; i < MATSIZ; ++i) {
			for (int j = 0; j < MATSIZ; ++j) {
				a[i][j] = val = val * (i+1) + (j+1);
				b[i][j] = val = val / (j+1) - (i+1);
			}
		}

		initialized = true;
	}

	// multiply matrices
	for (int i = 0; i < MATSIZ; ++i) {
		for (int j = 0; j < MATSIZ; ++j) {
			c[i][j] = 0;
			for (int k = 0; k < MATSIZ; ++k)
				c[i][j] += a[i][k] * b[k][j];
		}
	}

	// TODO here, try something that uses the same array (e.g. iterate x -> 3x + 1 to ptr[0] until you reach the same value) to avoid cache misses
#endif

	/*
	for (int i = 0; i < MATSIZ; ++i) {
		delete[] a[i];
		delete[] b[i];
		delete[] c[i];
	}

	delete[] a;
	delete[] b;
	delete[] c;
	*/
}