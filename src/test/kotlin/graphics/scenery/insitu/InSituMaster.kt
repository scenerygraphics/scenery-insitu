package graphics.scenery.insitu

import graphics.scenery.BufferUtils
import graphics.scenery.SceneryBase
import org.zeromq.ZContext
import org.zeromq.ZMQ
import java.nio.ByteBuffer

@Suppress("unused")
class InSituMaster : SceneryBase("In situ master") {

    var insituRunning = true

    private external fun transmitVisMsg(pose: ByteBuffer, size: Int)
    private external fun transmitSteerMsg(pose: ByteBuffer, size: Int)

    @Suppress("unused")
    fun listenForMessages() {
        val context = ZContext(4)
        var subscriber: ZMQ.Socket = context.createSocket(ZMQ.SUB)
        val address = "tcp://localhost:6655"
        subscriber.connect(address)
        subscriber.subscribe(ZMQ.SUBSCRIPTION_ALL)

        var buffer = ByteBuffer.allocateDirect(0)

        while(insituRunning) {
            val payload = subscriber.recv()
            if (payload != null) {
                if(buffer.capacity() != payload.size) {
                    buffer = BufferUtils.allocateByteAndPut(payload)
                } else {
                    buffer.put(payload).flip()
                }

                //if the payload contains vis message
                transmitVisMsg(buffer, buffer.capacity())

            } else {
                logger.info("Payload received but is null")
            }

        }

    }
}