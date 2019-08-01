package graphics.scenery.insitu.benchmark

// package benchmark

import mpi.MPIException
import mpi.MPI
import org.junit.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.*
import java.io.*

class ShmConsumer {

    lateinit var result: LongBuffer
    val duration = 30 // seconds
    val period = 5L // milliseconds
    val iters = duration*1000/period
    lateinit var file: FileWriter
    var prodTimeOffset = -1L // offset from which to measure producer time
    var consTimeOffset = -1L // offset from which to measure own time
    var i = 0

    private fun init() {
        prodTimeOffset = result.get(0)
        consTimeOffset = System.nanoTime() / 1000
        println("prod: ${prodTimeOffset}\tcons: ${consTimeOffset}")
    }

    private fun update() {
        val stop = System.nanoTime() / 1000
        val start = result.get(0)
        val diff = (stop - consTimeOffset) - (start - prodTimeOffset) // to account for phase difference

        i++
        if (i % 200 == 0)
            println("prod: ${start}\tcons: ${stop}")

        file.write("${diff}\n")
    }

    private fun terminate() {
        deleteShm()
        file.close()
    }

    private external fun sayHello(): Int
    private external fun getSimData(worldRank:Int): ByteBuffer

    private external fun deleteShm()

    @Test
    fun main() {

        val nullArg = arrayOfNulls<String>(0)
        MPI.Init(nullArg)

        val myrank = MPI.COMM_WORLD.rank
        val size = MPI.COMM_WORLD.size
        val pName = MPI.COMM_WORLD.name
        if (myrank == 0)
            println("Hi, I am Aryaman's MPI example")
        else
            println("Hello world from $pName rank $myrank of $size")
        val shmrank = myrank + 3

        System.loadLibrary("shmConsumer")
        val log = LoggerFactory.getLogger("JavaMPI")
        log.info("Hi, I am Aryaman's shared memory example")

        File("benchmark_logs").mkdir()
        file = FileWriter("benchmark_logs/kotlin_log0.csv", false);

        try {
            val a = this.sayHello()
            log.info(a.toString());
            val start = System.nanoTime() / 1000
            val bb = this.getSimData(shmrank)
            val stop = System.nanoTime() / 1000

            bb.order(ByteOrder.nativeOrder())
            result = bb.asLongBuffer()

            println("JNI: ${result.get(1)}\tKotlin: ${stop-start}")

            /*
            while(result.hasRemaining())
                println(message = "Java says: ${result.get()}")
            result.rewind()
            */
            //this.deleteShm()


        } catch (e: MPIException) {
            e.printStackTrace()
        }

        init()

        for (i in 0..iters) {
            update()
            Thread.sleep(period)
        }

        log.info("Done here.")

        terminate()

        MPI.Finalize()
    }

}