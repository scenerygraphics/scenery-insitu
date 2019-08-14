#include "ShmAllocator.hpp"
#include <iostream>

int main()
{
	ShmAllocator alloc("/tmp", 0, true),
				 alloc1("/etc", 4, true);

	int *ptr[10], *ptr1[10];

	for (int i = 0; i < 10; ++i)
		ptr[i] = (int *) alloc.shm_alloc(10000);
	for (int i = 0; i < 10; ++i)
		ptr1[i] = (int *) alloc1.shm_alloc(10000);

	std::cout << "allocated" << std::endl;

	for (int i = 0; i < 10; ++i) {
		ptr[i][0] = 3;
		ptr1[i][10] = ptr[i][0];
	}

	std::cout << "wrote" << std::endl;

	for (int i = 0; i < 10; ++i)
		alloc.shm_free(ptr[i]);
	for (int i = 0; i < 10; ++i)
		alloc1.shm_free(ptr1[i]);

	std::cout << "freed" << std::endl;
}