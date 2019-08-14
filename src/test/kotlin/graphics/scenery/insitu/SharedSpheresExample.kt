package graphics.scenery.insitu

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

    val lock = ReentrantLock()
    var cont = true // whether to continue updating memory

    lateinit var color: GLVector

    override fun init() {
        settings.set("Input.SlowMovementSpeed", 0.5f)
        settings.set("Input.FastMovementSpeed", 1.0f)

        renderer = hub.add(SceneryElement.Renderer,
                Renderer.createRenderer(hub, applicationName, scene, 512, 512))

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
            val disp = (speed - 100f) / 50f // rescaling speed, between 0 and 2 (just for this particular simulation; otherwise need to know average and stddev)
            val scale = sqrt(5f) * disp / sqrt(1+disp*disp) // some sigmoidal scale factor, between 0 and 2

            // s.material.diffuse = color.times(scale) // color.times(.8.toFloat()).plus(direction.times(.2.toFloat()))
            // s.material.diffuse = direction
            s.material.diffuse = color.times(.5f).plus(direction.times(.5f)).times(scale)
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


        System.loadLibrary("shmSpheresTrial")
        val log = LoggerFactory.getLogger("JavaMPI")
        log.info("Hi, I am Aryaman's shared memory example")

        try {
            val a = this.sayHello()
            log.info(a.toString())

            shmRank = rank + 1 // later assign it based on myrank (should not be zero)
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
        super.main()
        log.info("Done here.")
    }

}