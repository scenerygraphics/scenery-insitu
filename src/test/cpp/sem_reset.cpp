// Reset state of semaphores for given rank

#include <stdio.h>

#include "../../../src/main/resources/SemManager.hpp"

#define RANK 3

int main(int argc, char *argv[])
{
	int rank = RANK;

	if (argc > 1) {
			rank = atoi(argv[1]);
	}

	SemManager sems("/", rank, true, true);

	printf("key\tsem 0\tsem 1\n");
	for (int i = 0; i < NKEYS; ++i) {
		sems.set(i, 0, 0);
		sems.set(i, 1, 0);
	}

	SemManager sems2("/home", rank, true, true);

	printf("key\tsem 0\tsem 1\n");
        for (int i = 0; i < NKEYS; ++i) {
	        sems2.set(i, 0, 0);
	        sems2.set(i, 1, 0);
         }
}
