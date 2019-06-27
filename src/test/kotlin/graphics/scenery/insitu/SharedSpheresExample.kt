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
import kotlin.concurrent.*

class SharedSpheresExample : SceneryBase("SharedSpheresExample"){

    lateinit var buffer: FloatBuffer // TODO later make IntBuffer
    lateinit var result: FloatBuffer
    lateinit var spheres: ArrayList<Sphere>
    var resrank = -1 // should never be this value in execution

    override fun init() {
        settings.set("Input.SlowMovementSpeed", 0.5f)
        settings.set("Input.FastMovementSpeed", 1.0f)

        renderer = hub.add(SceneryElement.Renderer,
                Renderer.createRenderer(hub, applicationName, scene, 512, 512))

        spheres = ArrayList()
        result.rewind()
        for (i in 0..1) result.get() // TODO remove later, once buffer and result are not tied together
        // while (result.remaining() > 3) {
        for (i in 0..30) {
            val s = Sphere(Random.randomFromRange(0.04f, 0.2f), 10)
            val x = result.get()
            val y = result.get()
            val z = result.get()
            println("x is $x y is $y z is $z")
            s.position = GLVector(x, y, z)
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
            update()
        }
    }

    private fun update() {
        // check buffer if memory location changed
        this.getResult()

        result.rewind()
        for (i in 0..1) result.get() // TODO remove later
        for (s in spheres) {
            val x = result.get()
            val y = result.get()
            val z = result.get()
            //println("x is $x y is $y z is $z")
            s.position = GLVector(x, y, z)
        }
    }

    private fun getResult() {
        // buffer.rewind()
        val newrank = buffer.get(0).toInt()
        if (resrank != newrank) {
            println("Old rank: $resrank\tNew rank: $newrank")
            // use (and delete for now) old memory, attach to new one
            // buffer.put(1, 1f)
            this.deleteShm()

            if (newrank < 0) {
                this.close()
            }
            resrank = newrank
            val bb = this.getSimData(resrank) // TODO this also changes buffer
            bb.order(ByteOrder.nativeOrder())
            result = bb.asFloatBuffer()
        }
    }

    private external fun sayHello(): Int
    private external fun getSimData(worldRank:Int): ByteBuffer

    private external fun deleteShm()

    @Test
    override fun main() {

        val nullArg = arrayOfNulls<String>(0)
        MPI.Init(nullArg)

        val myrank = MPI.COMM_WORLD.rank
        val size = MPI.COMM_WORLD.size
        val pName = MPI.COMM_WORLD.name
        if (myrank == 0)
            println("Hi, I am Aryaman's MPI example")
        else
            println("Hello world from $pName rank $myrank of $size")

        System.loadLibrary("shmSpheresTrial")
        val log = LoggerFactory.getLogger("JavaMPI")
        log.info("Hi, I am Aryaman's shared memory example")

        try {
            val a = this.sayHello()
            log.info(a.toString())

            val bb = this.getSimData(myrank)
            bb.order(ByteOrder.nativeOrder())
            buffer = bb.asFloatBuffer()
            println("Buffer rank: ${buffer.get(0)}")

            this.getResult()
            println(result.remaining())

            while(result.hasRemaining())
                println(message = "Java says: ${result.get()} (${result.remaining()})")
            result.rewind()

            //this.deleteShm()


        } catch (e: MPIException) {
            e.printStackTrace()
        }
        super.main()
        log.info("Done here.")
    }

}