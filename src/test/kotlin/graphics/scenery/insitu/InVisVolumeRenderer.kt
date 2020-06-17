package graphics.scenery.insitu

import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.net.NodePublisher
import graphics.scenery.net.NodeSubscriber
import graphics.scenery.numerics.Random
import graphics.scenery.utils.RingBuffer
import graphics.scenery.utils.extensions.plus
import graphics.scenery.volumes.BufferedVolume
import graphics.scenery.volumes.Colormap
import graphics.scenery.volumes.Volume
import net.imglib2.type.numeric.integer.UnsignedShortType
import org.joml.Vector3f
import org.junit.Test
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.fixedRateTimer
import kotlin.concurrent.thread
import kotlin.math.absoluteValue

class InVisVolumeRenderer: SceneryBase("InVisVolumeRenderer") {

    // The below will be updated by the C++ code
    var windowSize = 500
    var computePartners = 1
    var rank = 0

    lateinit var data: Array<ArrayList<ShortBuffer?>?> // An array of the size computePartners, each element of which is a list of ShortBuffers, the individual grids of the compute partner
    lateinit var allOrigins: Array<ArrayList<Vector3f>?> // An array of size computePartners, each element of which is a list of 3D vectors, the origins of the individual grids of the compute partner
    lateinit var allGridDims: Array<ArrayList<Array<Int>>?>
    lateinit var allGridDomains: Array<ArrayList<Array<Int>>?>
    lateinit var numGridsPerPartner: Array<Int?>
    lateinit var volumeHashMaps: Array<ArrayList<LinkedHashMap<String, ByteBuffer>?>?>
    lateinit var volumes: Array<ArrayList<BufferedVolume?>?>

    var numUpdated : Int = 0 // The number of partners that have updated data with pointers to their grids. Works only assuming no resizing on OpenFPM side
    val lock = ReentrantLock()
    var publishedNodes = ArrayList<Node>()

    @Suppress("unused")
    fun initializeArrays() {
        data = arrayOfNulls(computePartners)
        numGridsPerPartner = arrayOfNulls(computePartners)
        volumeHashMaps = arrayOfNulls(computePartners)
        volumes = arrayOfNulls(computePartners)
        allOrigins = arrayOfNulls(computePartners)
        allGridDims = arrayOfNulls(computePartners)
        allGridDomains = arrayOfNulls(computePartners)

        for(i in 0 until computePartners) {
            data[i] = ArrayList(0)
            volumeHashMaps[i] = ArrayList(0)
            volumes[i] = ArrayList(0)
            allOrigins[i] = ArrayList(0)
            allGridDims[i] = ArrayList(0)
            allGridDomains[i] = ArrayList(0)
        }
    }

    @Suppress("unused")
    fun updateData(grids: Array<ByteBuffer?>, origins: Array<Int>, gridDims: Array<Array<Int>>, numGrids: Int, partnerNo: Int) {
        numGridsPerPartner[partnerNo] = numGrids
        for(i in 0 until numGrids) {
            data[partnerNo]!!.add(grids[i] as ShortBuffer)
            allOrigins[partnerNo]?.add(Vector3f(origins[i*3].toFloat(), origins[i*3+1].toFloat(), origins[i*3+2].toFloat()))
            allGridDims[partnerNo]?.add(arrayOf(
                    gridDims[i][0], gridDims[i][1], gridDims[i][2], // starting position of grid
                    gridDims[i][3], gridDims[i][4], gridDims[i][5])) // end coordinate of grid
            allGridDomains[partnerNo]?.add(arrayOf(
                    gridDims[i][6], gridDims[i][7], gridDims[i][8], // starting position of domain
                    gridDims[i][9], gridDims[i][10], gridDims[i][11])) // end coordinate of domain

        }
        lock.lock()
        numUpdated++
        lock.unlock()
    }

    override fun init() {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            position = Vector3f(0.0f, 0.5f, 5.0f)
            perspectiveCamera(50.0f, windowWidth, windowHeight)

            scene.addChild(this)
        }

        val shell = Box(Vector3f(10.0f, 10.0f, 10.0f), insideNormals = true)
        shell.material.cullingMode = Material.CullingMode.None
        shell.material.diffuse = Vector3f(0.1f, 0.1f, 0.1f)
        shell.material.specular = Vector3f(0.0f)
        shell.material.ambient = Vector3f(0.0f)
        shell.position = Vector3f(0.0f, 4.0f, 0.0f)
        scene.addChild(shell)

        val lights = (0 until 3).map {
            PointLight(radius = 15.0f)
        }

        lights.mapIndexed { i, light ->
            light.position = Vector3f(2.0f * i - 4.0f,  i - 1.0f, 0.0f)
            light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
            light.intensity = 0.2f
            scene.addChild(light)
        }

        // Create VolumeBuffer objects for each grid, configure them, and put them in the scene
        val volumesInitialized = false
        thread {
            while(numUpdated != computePartners) {
                Thread.sleep(50)
            }

            for (partnerNo in 0 until computePartners) {
                val numGrids = numGridsPerPartner[partnerNo]!!
                val gridDims = allGridDims[partnerNo]!! // A list of dimensions of all the grids worked on by this computePartner
                val gridDomains = allGridDomains[partnerNo]!!
                val origins = allOrigins[partnerNo]!!

                for(grid in 0 until numGrids) {
                    volumeHashMaps[partnerNo]?.add(LinkedHashMap())
                    val width = gridDims[grid][3] - gridDims[grid][0]
                    val height = gridDims[grid][4] - gridDims[grid][1]
                    val depth = gridDims[grid][5] - gridDims[grid][2]
                    val currentHasMap = volumeHashMaps[partnerNo]?.get(grid)!!
                    volumes[partnerNo]?.add( Volume.fromBuffer(currentHasMap, width.absoluteValue, height.absoluteValue, depth.absoluteValue, UnsignedShortType(), hub))
                    volumes[partnerNo]?.get(grid)?.name  = "Grid${grid}OfPartner${partnerNo}"
                    volumes[partnerNo]?.get(grid)?.position = origins[grid]
                    volumes[partnerNo]?.get(grid)?.origin = Origin.FrontBottomLeft
                    volumes[partnerNo]?.get(grid)?.colormap = Colormap.get("hot")
                    volumes[partnerNo]?.get(grid)?.pixelToWorldRatio = 0.03f

                    with(volumes[partnerNo]?.get(grid)?.transferFunction) {
                        this?.addControlPoint(0.0f, 0.0f)
                        this?.addControlPoint(0.2f, 0.0f)
                        this?.addControlPoint(0.4f, 0.5f)
                        this?.addControlPoint(0.8f, 0.5f)
                        this?.addControlPoint(1.0f, 0.0f)
                    }

                    volumes[partnerNo]?.get(grid)?.metadata?.set("animating", true)
                    volumes[partnerNo]?.get(grid)?.let { scene.addChild(it)}

                    //TODO:Add the domain information to the volume

                }
            }

        }

        publishedNodes.add(cam)

        val publisher = hub.get<NodePublisher>(SceneryElement.NodePublisher)
        val subscriber = hub.get<NodeSubscriber>(SceneryElement.NodeSubscriber)

        publishedNodes.forEachIndexed { index, node ->
            publisher?.nodes?.put(13337 + index, node)

            subscriber?.nodes?.put(13337 + index, node)
        }

        fixedRateTimer(initialDelay = 5, period = 50) {
            if (volumesInitialized && running && !shouldClose) {
                updateVolumes()
            }
        }

    }

    fun updateVolumes() {
        for(partnerNo in 0 until computePartners) {
            val numGrids = numGridsPerPartner[partnerNo]!!
            val volumesFromThisPartner = volumes[partnerNo]!!
            val dataFromThisPartner = data[partnerNo]!!
            for(grid in 0 until numGrids) {
                if(volumesFromThisPartner[grid]?.metadata?.get("animating") == true) {
                    volumesFromThisPartner[grid]?.addTimepoint("time", dataFromThisPartner[grid] as ByteBuffer)
                    val currentHasMap = volumeHashMaps[partnerNo]?.get(grid)!!
                    volumesFromThisPartner[grid]?.goToTimePoint(currentHasMap.size-1)
                    volumesFromThisPartner[grid]?.purgeFirst(1, 1)
                }
            }
        }
    }

    @Test
    override fun main() {
        System.setProperty("scenery.MasterNode", "tcp://127.0.0.1:6666")
        System.setProperty("scenery.master", "false")
//        System.setProperty("scenery.Headless", "true")
        super.main()
    }
}