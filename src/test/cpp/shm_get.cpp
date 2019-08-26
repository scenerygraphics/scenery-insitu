#include <stdio.h>
#include <stdlib.h>

#include "SemManager.hpp"

#define RANK 3

int main(int argc, char *argv[])
{
	int rank = RANK;

	if (argc > 1) {
		rank = atoi(argv[1]);
	}

	SemManager sems("/tmp", rank, true, false);

	printf("key\tsem 0\tsem 1\n");
	for (int i = 0; i < NKEYS; ++i) {
		printf("%d\t%d\t%d\n", i, sems.get(i, 0), sems.get(i, 1));
	}
}
