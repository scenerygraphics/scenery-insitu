package graphics.scenery.insitu

import cleargl.GLTypeEnum
import cleargl.GLVector
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.jogamp.opengl.math.Quaternion
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.net.NodePublisher
import graphics.scenery.net.NodeSubscriber
import graphics.scenery.numerics.Random
import mpi.MPI
import mpi.MPIException
import mpi.Request
import org.junit.Ignore
import org.junit.Test
import org.lwjgl.system.MemoryUtil
import org.msgpack.jackson.dataformat.MessagePackFactory
import org.slf4j.LoggerFactory
import org.zeromq.ZContext
import org.zeromq.ZMQ
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.DoubleBuffer
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.fixedRateTimer
import kotlin.concurrent.thread
import kotlin.concurrent.timer
import kotlin.math.sqrt


class Head : SceneryBase("InVis Head") {

    val windowSize = 700

    var publishedNodes = ArrayList<Node>()
    val log = LoggerFactory.getLogger("JavaMPI")

    val fsb = NaiveCompositor()
    var cam: Camera = DetachedHeadCamera()

    override fun init() {
        settings.set("Input.SlowMovementSpeed", 0.5f)
        settings.set("Input.FastMovementSpeed", 1.0f)

        renderer = hub.add(SceneryElement.Renderer,
                Renderer.createRenderer(hub, applicationName, scene, windowSize, windowSize))

        scene.addChild(fsb)

        val light = PointLight(radius = 15.0f)
        light.position = GLVector(0.0f, 0.0f, 2.0f)
        light.intensity = 10.0f
        light.emissionColor = GLVector(1.0f, 1.0f, 1.0f)
        scene.addChild(light)

        with(cam) {
            position = GLVector(0.0f, 0.0f, 5.0f)
            perspectiveCamera(50.0f, 512.0f, 512.0f)
            active = true

            scene.addChild(this)
        }

        publishedNodes.add(cam)

        val publisher = hub.get<NodePublisher>(SceneryElement.NodePublisher)
        val subscriber = hub.get<NodeSubscriber>(SceneryElement.NodeSubscriber)

        publishedNodes.forEachIndexed { index, node ->
            publisher?.nodes?.put(13337 + index, node)

            subscriber?.nodes?.put(13337 + index, node)
        }
    }

    @Suppress("unused")
    fun composite(image: Array<ByteBuffer?>, commSize: Int) {
        for (rank in 1 until commSize) {
            val colorName = "ColorBuffer$rank"
            val depthName = "DepthBuffer$rank"
            image[rank-1]?.position(0)
            val color = image[rank-1]?.duplicate()
            color?.limit(windowSize * windowSize * 3)
            image[rank-1]?.position(windowSize * windowSize * 3)
            val depth = image[rank-1]?.slice()
            image[rank-1]?.position(0)
            fsb.material.textures[colorName] = "fromBuffer:$colorName"
            fsb.material.transferTextures[colorName] = GenericTexture("whatever", GLVector(windowSize.toFloat(), windowSize.toFloat(), 1.0f), 3, contents = color)
            fsb.material.textures[depthName] = "fromBuffer:$depthName"
            fsb.material.transferTextures[depthName] = GenericTexture("whatever", GLVector(windowSize.toFloat(), windowSize.toFloat(), 1.0f), 1, type = GLTypeEnum.Float, contents = depth)
            fsb.material.needsTextureReload = true
        }
    }

    @Suppress("unused")
    fun adjustCamera() {
        val context = ZContext(4)
        var subscriber: ZMQ.Socket = context.createSocket(ZMQ.SUB)
        val address = "tcp://localhost:6655"
        subscriber.connect(address)
        subscriber.subscribe(ZMQ.SUBSCRIPTION_ALL)

        val objectMapper = ObjectMapper(MessagePackFactory())

        while (true) {
            val payload = subscriber.recv()
            if (payload != null) {
                val deserialized: List<Any> = objectMapper.readValue(payload, object : TypeReference<List<Any>>() {})

                cam.rotation = stringToQuaternion(deserialized[0].toString())
                cam.position = stringTo3DGLVector(deserialized[1].toString())

                println("The rotation is: ${cam.rotation}")
                println("The position is: ${cam.position}")

            } else {
                log.info("received payload but it is null")
            }
        }
    }

    private fun stringToQuaternion(inputString: String): Quaternion {
        val elements = inputString.removeSurrounding("[", "]").split(",").map { it.toFloat() }
        return Quaternion(elements[0], elements[1], elements[2], elements[3])
    }

    private fun stringTo3DGLVector(inputString: String): GLVector {
        val mElements = inputString.removeSurrounding("[", "]").split(",").map { it.toFloat() }
        return GLVector(mElements[0], mElements[1], mElements[2])
    }

    @Test
    override fun main() {
        System.setProperty("scenery.master", "true")
        settings.set("VideoEncoder.StreamVideo", true)
        settings.set("H264Encoder.StreamingAddress", "udp://192.168.0.200:3337")
        System.setProperty("scenery.MasterNode", "tcp://127.0.0.1:6666")
        println("Head: Calling super.main!")
        super.main()
    }

}