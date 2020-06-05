package graphics.scenery.insitu

import org.junit.Test
import java.nio.ByteBuffer

class JNITest {
    @Test
    fun main() {
        var b1 = ByteBuffer.allocateDirect(0)
        println("Capacity of b1 initially is ${b1.capacity()}")

        val b2 = ByteBuffer.allocateDirect(10)
        println("Capacity of b2 is ${b2.capacity()}")

        b1 = b2

        println("Capacity of b1 finally is ${b1.capacity()}")
    }
}