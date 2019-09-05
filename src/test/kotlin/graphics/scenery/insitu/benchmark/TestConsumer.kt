package graphics.scenery.insitu.benchmark

import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.system.measureNanoTime

class TestConsumer {

    val minsize = 1024L
    val sizelen = 21 // 22
    val iters = 5000 // 5000 // TODO test with higher iterations with computation every iteration and without computation, put on existing graph
    val filename = "/test.mmap"

    var len: Long = 0
    var size: Long = minsize * (1 shl (sizelen - 1)) // 0
    lateinit var comm : IPCComm

    external fun semWait()
    external fun semSignal()

    external fun sysvInit(size: Long) : ByteBuffer
    external fun sysvTerm()

    @Test
    fun main() {
        // load dynamic library
        System.loadLibrary("testConsumer")

        comm = Sem() // SysVMemory() // PosixMemory()

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

    inner class Sem : IPCComm() {
        override fun init() {}
        override fun recv() { semWait() }
        override fun term() {}
    }

    abstract inner class SharedMemory : IPCComm() {
        lateinit var buf: FloatBuffer

        var oldlen = 0L
        var oldval = 0f

        override fun recv() {
            // loop on last element of buffer
            if (oldlen == len)
                while (oldval == buf.get(len.toInt() - 1));
            oldlen = len
            oldval = buf.get(len.toInt() - 1)
            // alternatively plot read+write time for heap vs. shm, on one program writing then reading or timing writing and reading separately
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

    inner class PosixMemory : SharedMemory() {
        override fun init() {
            println("opening file $filename")
            val file = File(filename); // RandomAccessFile(filename, "r")
            val channel = FileChannel.open(file.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
            val ptr = channel.map(FileChannel.MapMode.READ_WRITE, 0, size.toLong());

            for (i in 1..10)
                println(ptr.get())
            ptr.rewind()
            buf = ptr.asFloatBuffer()
            for (i in 1..10)
                println(buf.get())
            buf.rewind()
            println("buffer size: ${buf.remaining()}")
        }

        override fun term() {
            // detach()
        }
    }

    abstract inner class FileComm : IPCComm()// resource accessed through filesystem
    // open file, read from file

}
