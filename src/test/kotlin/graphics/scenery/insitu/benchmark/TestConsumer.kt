package graphics.scenery.insitu.benchmark

// package benchmark

import mpi.MPIException
import mpi.MPI
import org.junit.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.*
import java.io.*

class TestConsumer {

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
        file.close()
    }
    
    private external fun sysvReceive(size: Int)
    private external fun mmapReceive(size: Int)

    private fun pipeInit() {
        // open file
    }
    private fun pipeReceive(size: Int) {
        // read from file
    }
    private fun pipeTerm() {
        // close file
    }

    private fun tcpInit() {
        // open socket
    }
    private fun tcpReceive(size: Int) {
        // read from socket
    }
    private fun tcpTerm() {
        // close socket
    }

    @Test
    fun main() {

        val nullArg = arrayOfNulls<String>(0)
        MPI.Init(nullArg)

        val myrank = MPI.COMM_WORLD.rank
        val size = MPI.COMM_WORLD.size
        val pName = MPI.COMM_WORLD.name

        System.loadLibrary("testConsumer")
        val log = LoggerFactory.getLogger("JavaMPI")

        File("benchmark_logs").mkdir()
        file = FileWriter("benchmark_logs/kotlin_log0.csv", false);

        val start = System.nanoTime() / 1000
        this.sysvReceive(10)
        val stop = System.nanoTime() / 1000

        println("JNI: ${result.get(1)}\tKotlin: ${stop-start}")


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