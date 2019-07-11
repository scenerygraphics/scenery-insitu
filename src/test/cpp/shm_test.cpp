// Test producer using allocator class

#include <iostream>
#include <sys/ipc.h>
#include <sys/shm.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdlib.h>
#include <stdio.h>
#include <errno.h>
#include <signal.h>

#include "ShmAllocator.hpp"

#define SIZE 10000
#define RANK 3

int cont;
float *str = NULL;
ShmAllocator alloc("/tmp", RANK);

void initstr() // modify this to copy old state to new array
{
	for (int i = 0; i < SIZE/sizeof(float); ++i) {
		str[i] = ((7*i) % 20 - 10) / 5.0;
	}
}

int update()
{
	static int cnt = 0;

	// move each entry in array based on bits of cnt
	for (int i = 0; i < SIZE/sizeof(float); ++i) {
		str[i] += 0.02 * ((cnt & (1 << (i & ((1 << 4) - 1)))) ? 1 : -1);
	}

	++cnt;

	return cont; // whether to continue
}
void reall()
{
	// generate new key
	float *str1 = str;

	// detach from old shm, release semaphore // detach after attaching to new, and copy from old to new
	alloc.shm_free(str1); // need not check for null

	str = (float *) alloc.shm_alloc(SIZE);
	initstr();
}

void detach(int signal)
{
	char c;
	std::cout << "Reallocate? ";
	std::cin >> c;
	if (c == 'y') {
		reall();
		std::cout << "Data written into memory: " << str[0] << std::endl;
	} else {
		printf("\n");
		cont = 0;
	}
}

int main()
{
	str = NULL;
	reall();

	std::cout << "Data written into memory: " << str[0] << std::endl;

	std::cin.get();

	signal(SIGINT, detach);

	cont = 1;
	while (update())
		usleep(10000);

	alloc.shm_free(str);
}
