package graphics.scenery.insitu

import mpi.MPI;
import mpi.MPIException;
import cleargl.GLVector
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.numerics.Random
import org.junit.Test

class DistributedSpheresExample : SceneryBase("DistributedSpheresExample") {
    override fun init() {


        renderer = hub.add(SceneryElement.Renderer,
                Renderer.createRenderer(hub, applicationName, scene, 512, 512))

        for(i in 0 until 2) {
            val s = Sphere(Random.randomFromRange(0.04f, 0.2f), 10)
            s.position = Random.randomVectorFromRange(3, -5.0f, 5.0f)
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
        super.main()
    }
}