package graphics.scenery.insitu

import graphics.scenery.BufferUtils
import graphics.scenery.SceneryBase
import org.zeromq.ZContext
import org.zeromq.ZMQ
import java.nio.ByteBuffer

class InSituMaster : SceneryBase("In situ master") {

    var insituRunning = true

    private external fun transmitVisMsg(pose: ByteBuffer)
    private external fun transmitSteerMsg(pose: ByteBuffer)

    @Suppress("unused")
    fun listenForMessages() {
        val context = ZContext(4)
        var subscriber: ZMQ.Socket = context.createSocket(ZMQ.SUB)
        val address = "tcp://localhost:6655"
        subscriber.connect(address)
        subscriber.subscribe(ZMQ.SUBSCRIPTION_ALL)

        while(insituRunning) {
            val payload = subscriber.recv()
            if (payload != null) {
                val buffer = BufferUtils.allocateByteAndPut(payload)

                //if the payload contains camera pose
                transmitVisMsg(buffer)

            } else {
                logger.info("Payload received but is null")
            }

        }

    }
}