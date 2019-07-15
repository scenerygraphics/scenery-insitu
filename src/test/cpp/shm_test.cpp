// Test producer using allocator class

#include <iostream>
#include <unistd.h>
#include <fcntl.h>
#include <stdlib.h>
#include <stdio.h>
#include <signal.h>

#include "ShmAllocator.hpp"

#define SIZE 10000
#define RANK 3
#define UPDPER 10000
#define REALLPER 50

int cont;
float *str = NULL, *str1 = NULL;
ShmAllocator alloc("/tmp", RANK);

void initstr() // modify this to copy old state to new array
{
	if (str1 == NULL) {
		for (int i = 0; i < SIZE/sizeof(float); ++i) {
			str[i] = ((7*i) % 20 - 10) / 5.0;
		}
	} else {
		for (int i = 0; i < SIZE/sizeof(float); ++i) {
			str[i] = str1[i];
		}
	}
}

void detach(int signal);
void reall();

int update()
{
	static int cnt = 0;

	// move each entry in array based on bits of cnt
	for (int i = 0; i < SIZE/sizeof(float); ++i) {
		str[i] += 0.02 * ((cnt & (1 << (i & ((1 << 4) - 1)))) ? 1 : -1);
	}

	++cnt;

	if (cnt % REALLPER == 0)
		reall();
	// 	detach(0);

	return cont; // whether to continue
}

void reall()
{
	// generate new key
	str1 = str;

	str = (float *) alloc.shm_alloc(SIZE);
	initstr();

	// detach from old shm, release semaphore // detach after attaching to new, and copy from old to new
	alloc.shm_free(str1); // need not check for null
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
		usleep(UPDPER);

	alloc.shm_free(str);
}
