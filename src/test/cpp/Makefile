# Executables producer and consumer, can be run by themselves or by mpirun

all: producer consumer

producer:
	mpic++ shm_mpiproducer.cpp ShmAllocator.cpp SemManager.cpp -std=c++11 -pthread -o producer

consumer:
	mpic++ shm_mpiconsumer.cpp ShmBuffer.cpp    SemManager.cpp -std=c++11 -pthread -o consumer

# producer:
# 	g++    shm_producer.cpp    ShmAllocator.cpp SemManager.cpp -std=c++11 -pthread -o producer

# consumer:
# 	g++    shm_consumer.cpp    ShmBuffer.cpp    SemManager.cpp -std=c++11 -pthread -o consumer

clean:
	rm -f producer consumer 
