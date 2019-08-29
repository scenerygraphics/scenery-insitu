package graphics.scenery.insitu.benchmark

import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.system.measureNanoTime

class TestConsumer {

    val minsize = 1024
    val sizelen = 15 // 22
    val iters = 100 // 5000

    var len: Int = 0
    var size: Int = minsize * (1 shl (sizelen - 1)) // 0
    lateinit var comm : IPCComm

    external fun semWait()
    external fun semSignal()

    external fun sysvInit(size: Int) : ByteBuffer
    external fun sysvTerm()

    @Test
    fun main() {
        // load dynamic library
        System.loadLibrary("testConsumer")

        comm = SysVMemory()

        // wait for producer, signal that you are ready
        semWait()
        semSignal()

        // initialize channel
        var time = measureNanoTime {
            semWait()
            comm.init()
            semSignal()
        }
        println("init time: ${time/1000} us")

        size = minsize
        for (i in 1..sizelen) {
            println("testing with size ${size} bytes")
            len = size / 4

            // iterate
            time = measureNanoTime {
                for (j in 1..iters) {
                    comm.recv()
                    semSignal()
                }
            } / iters

            println("size: $size\ttime: ${time/1000} us")

            size *= 2
        }

        // delete channel
        time = measureNanoTime {
            semWait()
            comm.term()
            semSignal()
        }
        println("term time: ${time/1000} us")
    }

    abstract inner class IPCComm {
        abstract fun init()
        abstract fun recv()
        abstract fun term()
    }

    abstract inner class SharedMemory : IPCComm() {
        lateinit var buf: FloatBuffer

        var oldlen = 0
        var oldval = 0f

        override fun recv() {
            // loop on last element of buffer
            if (oldlen == len)
                while (oldval == buf.get(len - 1));
            oldlen = len
            oldval = buf.get(len - 1)
        }
    }

    // first test sysv in java vs. sysv in c++
    // then sysv vs. mmap in java with computation
    // then maybe tcp
    // then init inside loop in java
    inner class SysVMemory : SharedMemory() {
        override fun init() {
            println("creating byte buffer with size $size")
            val ptr = sysvInit(size)
            ptr.order(ByteOrder.nativeOrder())
            buf = ptr.asFloatBuffer()
            buf.rewind()
            println("buffer size: ${buf.remaining()}")
        }

        override fun term() {
            sysvTerm()
        }
    }

    /*
    inner class PosixMemory : SharedMemory() {
        external fun attach(len: Long): ByteBuffer
        external fun detach()

        override fun init() {
            val ptr = attach(size)
            buf = ptr.asFloatBuffer()
        }

        override fun term() {
            detach()
        }
    }

    abstract inner class FileComm : IPCComm()// resource accessed through filesystem
    // open file, read from file

     */
}
