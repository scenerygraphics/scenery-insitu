// Reset state of semaphores for given rank

#include <stdio.h>

#include "SemManager.hpp"

#define RANK 3

int main(int argc, char *argv[])
{
	int rank = RANK;

	if (argc > 1) {
			rank = atoi(argv[1]);
	}

	SemManager sems("/tmp", rank, true, true);

	printf("key\tsem 0\tsem 1\n");
	for (int i = 0; i < NKEYS; ++i) {
		sems.set(i, 0, 0);
		sems.set(i, 1, 0);
	}
}
