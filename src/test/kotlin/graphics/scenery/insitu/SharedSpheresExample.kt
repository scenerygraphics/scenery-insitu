package graphics.scenery.insitu

import cleargl.GLTypeEnum
import cleargl.GLVector
import mpi.MPIException
import mpi.MPI
import org.junit.Test
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.numerics.Random
import org.jruby.java.proxies.`MapJavaProxy$INVOKER$i$default_value_get`
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.*
import kotlin.math.sqrt
import kotlin.system.exitProcess

class SharedSpheresExample : SceneryBase("SharedSpheresExample"){

    // lateinit var buffer: IntBuffer
    lateinit var data: DoubleBuffer
    lateinit var props: DoubleBuffer
    lateinit var spheres: ArrayList<Sphere>
    var rank = -1
    var size = -1
    var shmRank = -1 // should be calculated from rank

    // stats
    var count = 0L
    var sum = 0f
    var min = Float.MAX_VALUE
    var max = Float.MIN_VALUE

    val lock = ReentrantLock()
    var cont = true // whether to continue updating memory

    lateinit var color: GLVector

    override fun init() {
        settings.set("Input.SlowMovementSpeed", 0.5f)
        settings.set("Input.FastMovementSpeed", 1.0f)

        renderer = hub.add(SceneryElement.Renderer,
                Renderer.createRenderer(hub, applicationName, scene, 512, 512))

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
            box.material.diffuse = GLVector(1.0f, 1.0f, 1.0f)
            box.material.cullingMode = Material.CullingMode.Front
            scene.addChild(box)

            val light = PointLight(radius = 15.0f)
            light.position = GLVector(0.0f, 0.0f, 2.0f)
            light.intensity = 100.0f
            light.emissionColor = GLVector(1.0f, 1.0f, 1.0f)
            scene.addChild(light)

            val cam: Camera = DetachedHeadCamera()
            with(cam) {
                position = GLVector(0.0f, 0.0f, 5.0f)
                perspectiveCamera(50.0f, 512.0f, 512.0f)
                active = true

                scene.addChild(this)
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

            val cam: Camera = DetachedHeadCamera()
            with(cam) {
                position = GLVector(0.0f, 0.0f, 5.0f)
                perspectiveCamera(50.0f, 512.0f, 512.0f)
                active = true

                scene.addChild(this)
            }

            thread {
                while(true) {
                    val image = ByteArray(512 * 512 * 3 + 512 * 512 * 4)

//                    val result = BufferedImage(512, 512, BufferedImage.TYPE_3BYTE_BGR)
//                    result.data = Raster.createRaster(result.getSampleModel(), DataBufferByte(image.sliceArray(0..512*512*3), 512 * 512 * 3), Point() )

//                    ImageIO.write(result, "png", File("/Users/argupta/result1.png"))
//                    FileOutputStream(File("/Users/argupta/depth1.raw")).write(image, 512 * 512 * 3, 512 * 512 * 4)
//                    result.data = Raster.createRaster(result.getSampleModel(), DataBufferByte(image.sliceArray(0..512*512*3), 512 * 512 * 3), Point() )

//                    ImageIO.write(result, "png", File("/Users/argupta/result2.png"))
//                    FileOutputStream(File("/Users/argupta/depth2.raw")).write(image, 512 * 512 * 3, 512 * 512 * 4)
//                fsb.material.textures["color1"] = "fromBuffer:color1"
//                fsb.material.transferTextures["color1"] = GenericTexture("whatever", contents = whatComesOverTheNetwork)

                    val dimensions = GLVector()

                    for(rank in 1 until MPI.COMM_WORLD.size) {
                        MPI.COMM_WORLD.recv(image, 512 * 512 * 3 + 512 * 512 * 4, MPI.BYTE, rank, 0)
                        logger.debug("received from process $rank")
                        val colorName = "ColorBuffer$rank"
                        val depthName = "DepthBuffer$rank"
                        val color = ByteBuffer.wrap(image.sliceArray(0..512*512*3))
                        val depth = ByteBuffer.wrap(image.sliceArray(512*512*3 until image.size))
                        fsb.material.textures[colorName] = "fromBuffer:$colorName"
                        fsb.material.transferTextures[colorName] = GenericTexture("whatever", GLVector(512.0f, 512.0f, 1.0f), 3, contents = color)

                        fsb.material.textures[depthName] = "fromBuffer:$depthName"
                        fsb.material.transferTextures[depthName] = GenericTexture("whatever", GLVector(512.0f, 512.0f, 1.0f), 1, type = GLTypeEnum.Float, contents = depth)
                        fsb.material.needsTextureReload = true
                    }
                }
            }
        }

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
        MPI.Init(nullArg)

        rank = MPI.COMM_WORLD.rank
        size = MPI.COMM_WORLD.size
        val pName = MPI.COMM_WORLD.name
        if (rank == 0)
            println("Hi, I am Aryaman's MPI example")
        else
            println("Hello world from $pName rank $rank of $size")

        val log = LoggerFactory.getLogger("JavaMPI")

        if(MPI.COMM_WORLD.rank != 0) {
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