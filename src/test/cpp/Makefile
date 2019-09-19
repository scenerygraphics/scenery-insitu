# Executables producer and consumer, can be run by themselves or by mpirun

CPP_DIR := ../../main/resources

all: producer consumer alloctest sem_get sem_reset

producer:
	mpic++ -I$(CPP_DIR) shm_mpiproducer.cpp $(CPP_DIR)/ShmAllocator.cpp $(CPP_DIR)/SemManager.cpp -std=c++11 -pthread -o producer

consumer:
	mpic++ -I$(CPP_DIR) shm_mpiconsumer.cpp $(CPP_DIR)/ShmBuffer.cpp    $(CPP_DIR)/SemManager.cpp -std=c++11 -pthread -o consumer

alloctest:
	g++    -I$(CPP_DIR) alloctest.cpp       $(CPP_DIR)/ShmAllocator.cpp $(CPP_DIR)/SemManager.cpp -std=c++11 -pthread -o alloctest

sem_get:
	g++    -I$(CPP_DIR) sem_get.cpp   $(CPP_DIR)/SemManager.cpp -std=c++11 -pthread -o sem_get

sem_reset:
	g++    -I$(CPP_DIR) sem_reset.cpp $(CPP_DIR)/SemManager.cpp -std=c++11 -pthread -o sem_reset

# producer:
# 	g++    shm_producer.cpp    ShmAllocator.cpp SemManager.cpp -std=c++11 -pthread -o producer

# consumer:
# 	g++    shm_consumer.cpp    ShmBuffer.cpp    SemManager.cpp -std=c++11 -pthread -o consumer

clean:
	rm -f producer consumer alloctest sem_get sem_reset