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


class SharedSpheresExample : SceneryBase("SharedSpheresExample"){

    val windowSize = 700

    // lateinit var buffer: IntBuffer
    lateinit var data: DoubleBuffer
    lateinit var props: DoubleBuffer
    lateinit var spheres: ArrayList<Sphere>
    var rank = -1
    var commSize = -1
    var shmRank = -1 // should be calculated from rank

    // stats
    var count = 0L
    var sum = 0f
    var min = Float.MAX_VALUE
    var max = Float.MIN_VALUE

    val lock = ReentrantLock()
    var cont = true // whether to continue updating memory

    lateinit var color: GLVector
    var publishedNodes = ArrayList<Node>()
    val log = LoggerFactory.getLogger("JavaMPI")

    override fun init() {
        settings.set("Input.SlowMovementSpeed", 0.5f)
        settings.set("Input.FastMovementSpeed", 1.0f)

        renderer = hub.add(SceneryElement.Renderer,
                Renderer.createRenderer(hub, applicationName, scene, windowSize, windowSize))

        val r = renderer as Renderer
        r.activateParallelRendering()

        if(MPI.COMM_WORLD.rank != 0) {

            spheres = ArrayList()
            data.rewind()
            while (data.remaining() > 3) {
                val s = Sphere(Random.randomFromRange(0.04f, 0.02f), 10)
                val x = data.get().toFloat()
                val y = data.get().toFloat()
                val z = data.get().toFloat()
                // println("x is $x y is $y z is $z")
                s.position = GLVector(x, y, z)
                color = s.material.diffuse
                scene.addChild(s)
                spheres.add(s)
            }

            val box = Box(GLVector(10.0f, 10.0f, 10.0f), insideNormals = true)
            box.material.diffuse = GLVector(0.9f, 0.9f, 0.9f)
            box.material.cullingMode = Material.CullingMode.Front
            scene.addChild(box)

            val light = PointLight(radius = 15.0f)
            light.position = GLVector(0.0f, 0.0f, 2.0f)
            light.intensity = 4.0f
            light.emissionColor = GLVector(1.0f, 1.0f, 1.0f)
            scene.addChild(light)

            val cam: Camera = DetachedHeadCamera()
            with(cam) {
                position = GLVector(0.3513025f, 0.11647624f, 2.3089614f)
                perspectiveCamera(50.0f, 512.0f, 512.0f)
                active = true
                cam.rotation = Quaternion(-0.10018345f, 0.009550877f, -9.6172147E-4f, 0.9949227f)
                scene.addChild(this)
            }

            publishedNodes.add(cam)

            val publisher = hub.get<NodePublisher>(SceneryElement.NodePublisher)
            val subscriber = hub.get<NodeSubscriber>(SceneryElement.NodeSubscriber)

            publishedNodes.forEachIndexed { index, node ->
                publisher?.nodes?.put(13337 + index, node)

                subscriber?.nodes?.put(13337 + index, node)
            }


            fixedRateTimer(initialDelay = 5, period = 5) {
                lock.lock()
                if (cont)
                    update()
                else
                    cancel()
                lock.unlock()
            }

            // execute getResult again only after it has finished waiting
            timer(initialDelay = 10, period = 10) {
                if (cont) // may also synchronize cont with stop()
                    getData()
                else
                    cancel()
            }

            // execute getResult again only after it has finished waiting
            timer(initialDelay = 10, period = 10) {
                if (cont) // may also synchronize cont with stop()
                    getProps()
                else
                    cancel()
            }
        }

        if(MPI.COMM_WORLD.rank == 0) {
            val fsb = NaiveCompositor()
            // fsb.material = ShaderMaterial.fromFiles("depthCombiner.vert", "depthCombiner.frag")
            scene.addChild(fsb)

            val light = PointLight(radius = 15.0f)
            light.position = GLVector(0.0f, 0.0f, 2.0f)
            light.intensity = 10.0f
            light.emissionColor = GLVector(1.0f, 1.0f, 1.0f)
            scene.addChild(light)

            var cam: Camera = DetachedHeadCamera()
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

            thread {
                val requests: Array<Request?> = arrayOfNulls(commSize - 1)
                val image: Array<ByteBuffer?> = arrayOfNulls(commSize - 1)

                for (rank in 1 until commSize) {
                    image[rank-1] = MemoryUtil.memAlloc(windowSize * windowSize * 7)
                }

                while (true) {

                    for (rank in 1 until commSize) {
                        requests[rank-1] = MPI.COMM_WORLD.iRecv(image[rank - 1], windowSize * windowSize * 7, MPI.BYTE, rank, 0)
                    }

                    Request.waitAll(requests)

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
            }

            thread {
                val context = ZContext(4)
                var subscriber: ZMQ.Socket = context.createSocket(ZMQ.SUB)
                val address = "tcp://localhost:6655"
                subscriber.connect(address)
                subscriber.subscribe(ZMQ.SUBSCRIPTION_ALL)

                val objectMapper = ObjectMapper(MessagePackFactory())

                while(true) {
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

    private fun update() {
        // check buffer if memory location changed
        // this.getResult()

        data.rewind()
        props.rewind()
        for (s in spheres) {
            val x = data.get().toFloat()
            val y = data.get().toFloat()
            val z = data.get().toFloat()
            //println("x is $x y is $y z is $z")
            s.position = GLVector(x, y, z) // isn't this also a copy? can we just set s.position.mElements to a buffer?

            val vx = props.get().toFloat()
            val vy = props.get().toFloat()
            val vz = props.get().toFloat()
            val fx = props.get()
            val fy = props.get()
            val fz = props.get()

            val speed = GLVector(vx, vy, vz).magnitude()
            val direction = GLVector(vx, vy, vz).normalized

            // update statistics
            count++
            sum += speed
            if (min > speed)
                min = speed
            if (max < speed)
                max = speed

            val avg = sum / count
            val std = (max - min) / sqrt(12f) // simplistic assumption of uniform distribution

            // instead of just scaling speed linearly, apply sigmoid to get sharper blue and red
            val a = 5f // to scale sigmoid function applied to disp, the larger the value the sharper the contrast
            // val disp = (speed - avg) / (max - min) * 2*a // rescaling speed, between -a and a (just for this particular simulation; otherwise need to know average and stddev)
            val disp = (speed - avg) / std * a // speed / avg * a
            val mindisp = (min - avg) / std * a
            val scale = disp / sqrt(1+disp*disp) - mindisp / sqrt(1+mindisp*mindisp) // * sqrt(1+a*a) / a // some sigmoidal scale factor, between -1 and 1, average 0

            // s.material.diffuse = color.times(scale) // color.times(.8.toFloat()).plus(direction.times(.2.toFloat()))
            // s.material.diffuse = direction
            // s.material.diffuse = color.times(.5f).plus(direction.times(.5f)).times(scale)
            // s.material.diffuse = GLVector(127 * (1 + scale), 0f, 127 * (1 - scale)) // blue for low speed, red for high
            s.material.diffuse = GLVector(255 * scale, 0f, 255 * (1 - scale)) // blue for low speed, red for high
        }
    }

    private fun getData() {
        // communicate rank through semaphores instead of checking all the time
        // Two semaphores each for consumer and producer, one for each rank
        // Producer posts on the rank it is currently using, consumer sees it and changes array
        // Consumer then posts on old rank, producer sees and deletes it
        // Perhaps only two or even one semaphore may suffice, toggling rank at each update

        val bb = this.getSimData(false, shmRank) // waits until current shm is released and other shm is acquired
        bb.order(ByteOrder.nativeOrder())
        lock.lock()
        this.deleteShm(false)
        data = bb.asDoubleBuffer() // possibly set to data1, then at next update
        lock.unlock()
    }

    private fun getProps() {
        // communicate rank through semaphores instead of checking all the time
        // Two semaphores each for consumer and producer, one for each rank
        // Producer posts on the rank it is currently using, consumer sees it and changes array
        // Consumer then posts on old rank, producer sees and deletes it
        // Perhaps only two or even one semaphore may suffice, toggling rank at each update

        val bb = this.getSimData(true, shmRank) // waits until current shm is released and other shm is acquired
        bb.order(ByteOrder.nativeOrder())
        lock.lock()
        this.deleteShm(true)
        props = bb.asDoubleBuffer() // possibly set to data1, then at next update
        lock.unlock()
    }

    private external fun sayHello(): Int
    private external fun getSimData(isProp: Boolean, worldRank:Int): ByteBuffer

    private external fun deleteShm(isProp: Boolean)
    private external fun terminate()

    fun stop() {
        lock.lock()
        println("Acquired lock")
        deleteShm(false)
        deleteShm(true)
        terminate()
        println("Called terminate")
        cont = false
        lock.unlock()
        println("Released lock")
    }

    @Test
    override fun main() {

        val nullArg = arrayOfNulls<String>(0)
        val ret = MPI.InitThread(nullArg, MPI.THREAD_MULTIPLE)

        logger.info("MPI serial is: ${MPI.THREAD_MULTIPLE} and returned value is: $ret")

        rank = MPI.COMM_WORLD.rank
        commSize = MPI.COMM_WORLD.size
        val pName = MPI.COMM_WORLD.name
        if (rank == 0) {
            println("Hi, I am Aryaman's MPI example")
            System.setProperty("scenery.master", "true")
            settings.set("VideoEncoder.StreamVideo", true)
            settings.set("Distributed.Rank", rank)
        }
        else {
            println("Hello world from $pName rank $rank of $commSize")
            settings.set("Distributed.Rank", rank)
        }


        System.setProperty("scenery.MasterNode", "tcp://127.0.0.1:6666")

        if(MPI.COMM_WORLD.rank != 0) {
            System.setProperty("scenery.master", "false")
            System.setProperty("scenery.Headless", "true")
            System.loadLibrary("shmSpheresTrial")
            log.info("Hi, I am Aryaman's shared memory example")

            try {
                val a = this.sayHello()
                log.info(a.toString())

                shmRank = rank - 1// later assign it based on myrank (should not be zero)
                // MPI.COMM_WORLD.barrier() // wait for producer to allocate its memory
                this.getData()
                this.getProps()
                println(data.remaining())
                println(props.remaining())

                for (i in 1..30) // while(data.hasRemaining())
                    println(message = "Java says: ${data.get()} (${data.remaining()})")
                data.rewind()
                println()
                for (i in 1..30) // while(props.hasRemaining())
                    println(message = "Java says: ${props.get()} (${props.remaining()})")
                props.rewind()

                //this.deleteShm()


            } catch (e: MPIException) {
                e.printStackTrace()
            }
        }
        super.main()
        log.info("Done here.")
    }

}