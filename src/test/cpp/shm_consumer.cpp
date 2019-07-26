// Consumer test file

#include <signal.h>

#include "ShmBuffer.hpp"

#define RANK 3
#define SIZE 2024

bool cont = true;

void detach(int signal)
{
	cont = false;
}

int main()
{
	ShmBuffer buf("/tmp", RANK, SIZE, true);

	signal(SIGINT, detach);

	while (cont) {
		// buf.update_key();
		buf.attach();
		buf.detach(false);
	}
}