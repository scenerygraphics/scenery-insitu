package graphics.scenery.insitu

import com.jogamp.opengl.math.Quaternion
import graphics.scenery.*
import graphics.scenery.attribute.material.Material
import graphics.scenery.backends.Renderer
import graphics.scenery.net.NodePublisher
import graphics.scenery.net.NodeSubscriber
import org.joml.Vector3f
import org.junit.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.DoubleBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.fixedRateTimer
import kotlin.concurrent.thread
import kotlin.math.sqrt


class InVisRenderer : SceneryBase("InVisRenderer"){

    var windowSize = 500
    var computePartners = 1
    var rank = 0

    lateinit var data: Array<DoubleBuffer?>
    lateinit var props: Array<DoubleBuffer?>

    lateinit var spheres: Array<ArrayList<Sphere>?>

    // stats
    var count = 0L
    var sum = 0f
    var min = Float.MAX_VALUE
    var max = Float.MIN_VALUE

    val lock = ReentrantLock()
    var cont = true // whether to continue updating memory

//    lateinit var color: GLVector
    var publishedNodes = ArrayList<Node>()
    val log = LoggerFactory.getLogger("JavaMPI")

    override fun init() {
        settings.set("Input.SlowMovementSpeed", 0.5f)
        settings.set("Input.FastMovementSpeed", 1.0f)

        renderer = hub.add(SceneryElement.Renderer,
                Renderer.createRenderer(hub, applicationName, scene, windowSize, windowSize))

        val r = renderer as Renderer
        r.activateParallelRendering()

        thread {
            while(renderer?.firstImageReady == false) {
                Thread.sleep(50)
            }

            renderer?.recordMovie("./RendererMovie$rank.mp4")
            Thread.sleep(100000)
            renderer?.recordMovie()
        }

        val box = Box(Vector3f(10.0f, 10.0f, 10.0f), insideNormals = true)
        box.material().diffuse = Vector3f(0.9f, 0.9f, 0.9f)
        box.material().cullingMode = Material.CullingMode.Front
        scene.addChild(box)

        val light = PointLight(radius = 15.0f)
        light.position = Vector3f(0.0f, 0.0f, 2.0f)
        light.intensity = 4.0f
        light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
        scene.addChild(light)

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            position = Vector3f(0.3513025f, 0.11647624f, 2.3089614f)
            perspectiveCamera(50.0f, 512, 512)
//            active = true
//            cam.rotation = Quaternion(-0.10018345f, 0.009550877f, -9.6172147E-4f, 0.9949227f)
            scene.addChild(this)
        }

        publishedNodes.add(cam)

        val publisher = hub.get<NodePublisher>(SceneryElement.NodePublisher)
        val subscriber = hub.get<NodeSubscriber>(SceneryElement.NodeSubscriber)

        publishedNodes.forEachIndexed { index, node ->
//            publisher?.nodes?.put(13337 + index, node)

//            subscriber?.nodes?.put(13337 + index, node)
        }

        fixedRateTimer(initialDelay = 5, period = 5) {
            lock.lock()
            if (cont) {
                update()
            } else {
                cancel()
            }
            lock.unlock()
        }
    }

    @Suppress("unused")
    fun initializeArrays() {
        data = arrayOfNulls(computePartners)
        props = arrayOfNulls(computePartners)
        spheres = arrayOfNulls(computePartners)

        for(i in 0 until computePartners) {
            spheres[i] = ArrayList(0)
        }
    }

    private fun update() {
        // check buffer if memory location changed
        // this.getResult()

        for(c in 0 until computePartners) {
            val dat = data[c]?.rewind()
            val prop = props[c]?.rewind()

            var counter = 0

            if (dat == null || prop == null) {
                return
            }

//            while ( (dat.remaining() >= 3) && (prop.remaining() >= 6) ) {
////                println("Fetching data from the bytebuffers $c")
////                println("Data has ${data?.get(c)?.remaining()} remaining")
//
//                val x = dat.get().toFloat()
//                val y = dat.get().toFloat()
//                val z = dat.get().toFloat()
//                //println("x is $x y is $y z is $z")
//
//                val vx = prop.get().toFloat()
//                val vy = prop.get().toFloat()
//                val vz = prop.get().toFloat()
//                val fx = prop.get()
//                val fy = prop.get()
//                val fz = prop.get()

            while ( (data[c]!!.remaining() >= 3) && (props[c]!!.remaining() >= 6) ) {
//                println("Fetching data from the bytebuffers $c")
//                println("Data has ${data?.get(c)?.remaining()} remaining")

                val x = data[c]!!.get().toFloat()
                val y = data[c]!!.get().toFloat()
                val z = data[c]!!.get().toFloat()
                //println("x is $x y is $y z is $z")

                val vx = props[c]!!.get().toFloat()
                val vy = props[c]!!.get().toFloat()
                val vz = props[c]!!.get().toFloat()
                val fx = props[c]!!.get()
                val fy = props[c]!!.get()
                val fz = props[c]!!.get()
//                println("$vx $vy $vz $fx $fy $fz")

                val speed = Vector3f(vx, vy, vz).length()
                val direction = Vector3f(vx, vy, vz).normalize()

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
                val scale = disp / sqrt(1 + disp * disp) - mindisp / sqrt(1 + mindisp * mindisp) // * sqrt(1+a*a) / a // some sigmoidal scale factor, between -1 and 1, average 0

                if (counter < spheres[c]!!.size) {
                    // if sphere exists, update its position and color
                    spheres[c]!![counter].spatial().position = Vector3f(x, y, z) // isn't this also a copy? can we just set s.position.mElements to a buffer?
                    spheres[c]!![counter].material().diffuse = Vector3f(255 * scale, 0f, 255 * (1 - scale)) // blue for low speed, red for high
                } else {
                    // if sphere does not exist, create it and add it to the list
                    val s = Sphere(0.03f, 10)
                    s.spatial().position = Vector3f(x, y, z) // isn't this also a copy? can we just set s.position.mElements to a buffer?
                    s.material().diffuse = Vector3f(255 * scale, 0f, 255 * (1 - scale)) // blue for low speed, red for high
                    scene.addChild(s)
                    spheres[c]!!.add(s)
                }
                counter += 1
            }


            while (counter < spheres[c]!!.size) {
                // if there are still some spheres remaining in the list, but no data remaining in memory, remove the remaining spheres
                val s = spheres[c]!!.removeAt(counter)
                scene.removeChild(s)
            }
        }
    }

    @Suppress("unused")
    private fun updatePos(bb: ByteBuffer, compRank: Int) {
        // communicate rank through semaphores instead of checking all the time
        // Two semaphores each for consumer and producer, one for each rank
        // Producer posts on the rank it is currently using, consumer sees it and changes array
        // Consumer then posts on old rank, producer sees and deletes it
        // Perhaps only two or even one semaphore may suffice, toggling rank at each update

        logger.info("In updatePos Kotlin function")

        bb.order(ByteOrder.nativeOrder())
        println("Capacity of original bytebuffer is ${bb.capacity()}")
        lock.lock()
        println("Trying to update data buffer $compRank. I have the lock!")
        data[compRank] = bb.asDoubleBuffer() // possibly set to data1, then at next update
        println("Capacity of double bytebuffer is ${data[compRank]?.capacity()}")
        lock.unlock()
    }

    @Suppress("unused")
    private fun updateProps(bb: ByteBuffer, compRank: Int) {
        // communicate rank through semaphores instead of checking all the time
        // Two semaphores each for consumer and producer, one for each rank
        // Producer posts on the rank it is currently using, consumer sees it and changes array
        // Consumer then posts on old rank, producer sees and deletes it
        // Perhaps only two or even one semaphore may suffice, toggling rank at each update

        logger.info("In updateProps Kotlin function")

        bb.order(ByteOrder.nativeOrder())
        lock.lock()
        println("Trying to update props buffer $compRank. I have the lock!")
        props[compRank] = bb.asDoubleBuffer() // possibly set to data1, then at next update
        lock.unlock()
    }

//    fun stop() {
//        lock.lock()
//        println("Acquired lock")
//        deleteShm(false)
//        deleteShm(true)
//        terminate()
//        println("Called terminate")
//        cont = false
//        lock.unlock()
//        println("Released lock")
//    }

    @Test
    override fun main() {
        System.setProperty("scenery.MasterNode", "tcp://127.0.0.1:6666")
        System.setProperty("scenery.master", "false")
//        System.setProperty("scenery.Headless", "true")
        super.main()
    }
}