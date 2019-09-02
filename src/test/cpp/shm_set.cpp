#include <stdio.h>

#include "SemManager.hpp"

#define CONSEM 0
#define PROSEM 1
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
		sems.set(i, CONSEM, 0);
		sems.set(i, PROSEM, 0);
	}
}
