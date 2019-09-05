CPP_DIR := ../../../main/resources

all: producer consumer

producer:
	g++ -I$(CPP_DIR) test_producer.cpp $(CPP_DIR)/SemManager.cpp -o producer

consumer:
	g++ -I$(CPP_DIR) test_consumer.cpp $(CPP_DIR)/SemManager.cpp -o consumer

clean:
	rm producer consumer