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

class SharedSpheresExample : SceneryBase("SharedSpheresExample"){

    lateinit var result: FloatBuffer

    override fun init() {
        renderer = hub.add(SceneryElement.Renderer,
                Renderer.createRenderer(hub, applicationName, scene, 512, 512))



        while(result.remaining() > 3) {
            val s = Sphere(Random.randomFromRange(0.04f, 0.2f), 10)
            val x = result.get()
            val y = result.get()
            val z = result.get()
            println("x is $x y is $y z is $z")
            s.position = GLVector(x, y, z)
            scene.addChild(s)
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
    }

    private external fun sayHello(): Int
    private external fun getSimData(worldRank:Int): ByteBuffer

    private external fun deleteShm()

    @Test
    override fun main() {
        System.loadLibrary("shmSpheresTrial")
        val log = LoggerFactory.getLogger("JavaMPI")
        log.info("Hi, I am Aryaman's shared memory example")

        try {
            val a = this.sayHello()
            log.info(a.toString());
            val bb = this.getSimData(5)
            bb.order(ByteOrder.nativeOrder())

            result = bb.asFloatBuffer()

            while(result.hasRemaining())
                println(message = "Java says: ${result.get()}")
            result.rewind()

            //this.deleteShm()


        } catch (e: MPIException) {
            e.printStackTrace()
        }
        super.main()
        log.info("Done here.")
    }

}