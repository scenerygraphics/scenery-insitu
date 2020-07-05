package graphics.scenery.insitu

import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.backends.Shaders
import graphics.scenery.compute.ComputeMetadata
import graphics.scenery.compute.InvocationType
import graphics.scenery.textures.Texture
import graphics.scenery.utils.Image
import graphics.scenery.utils.extensions.plus
import graphics.scenery.volumes.BufferedVolume
import graphics.scenery.volumes.Colormap
import graphics.scenery.volumes.Volume
import graphics.scenery.volumes.VolumeManager
import net.imglib2.type.numeric.integer.UnsignedShortType
import org.joml.Quaternionf
import org.joml.Vector3f
import org.joml.Vector3i
import org.junit.Test
import org.lwjgl.system.MemoryUtil
import tpietzsch.shadergen.generate.SegmentTemplate
import tpietzsch.shadergen.generate.SegmentType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.math.absoluteValue

class InVisVolumeRenderer: SceneryBase("InVisVolumeRenderer") {

    // The below will be updated by the C++ code
    var windowSize = 500
    var computePartners = 1
    var rank = 0
    var commSize = 0

    lateinit var data: Array<ArrayList<ByteBuffer?>?> // An array of the size computePartners, each element of which is a list of ShortBuffers, the individual grids of the compute partner
    lateinit var allOrigins: Array<ArrayList<Vector3f>?> // An array of size computePartners, each element of which is a list of 3D vectors, the origins of the individual grids of the compute partner
    lateinit var allGridDims: Array<ArrayList<Array<Int>>?>
    lateinit var allGridDomains: Array<ArrayList<Array<Int>>?>
    lateinit var numGridsPerPartner: Array<Int?>
    lateinit var volumeHashMaps: Array<ArrayList<LinkedHashMap<String, ByteBuffer>?>?>
    lateinit var volumes: Array<ArrayList<BufferedVolume?>?>

    var numUpdated : Int = 0 // The number of partners that have updated data with pointers to their grids. Works only assuming no resizing on OpenFPM side
    val lock = ReentrantLock()
    var publishedNodes = ArrayList<Node>()
    val numSupersegments = 32;

    lateinit var CompositedVDIColour: Texture
    lateinit var CompositedVDIDepth: Texture
    lateinit var volumeManager: VolumeManager


    var count = 0
    val compute = Box()
    val maxSupersegments = 32
    val maxOutputSupersegments = 50

    private external fun distributeVDIs(subVDIColor: ByteBuffer, subVDIDepth: ByteBuffer, sizePerProcess: Int, commSize: Int)
    private external fun gatherCompositedVDIs(compositedVDIColor: ByteBuffer, compositedVDIDepth: ByteBuffer, root: Int, subVDILen: Int, myRank: Int, commSize: Int)

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
    fun updateData(partnerNo: Int, numGrids: Int, grids: Array<ByteBuffer?>, origins: IntArray, gridDims: IntArray, domainDims: IntArray) {
        numGridsPerPartner[partnerNo] = numGrids
        for(i in 0 until numGrids) {
            logger.info("Updating data for grid $i of compute partner $partnerNo")
            data[partnerNo]!!.add(grids[i])
            logger.info("Updated bytebuffer")
            logger.info("0: ${origins[0]}")
            logger.info("1: ${origins[1]}")
            logger.info("2: ${origins[2]}")
            allOrigins[partnerNo]?.add(Vector3f(origins[i*3].toFloat(), origins[i*3+1].toFloat(), origins[i*3+2].toFloat()))
            logger.info("Updated origin")
            allGridDims[partnerNo]?.add(arrayOf(
                    gridDims[i*6+0], gridDims[i*6+1], gridDims[i*6+2], // starting position of grid
                    gridDims[i*6+3], gridDims[i*6+4], gridDims[i*6+5])) // end coordinate of grid
            logger.info("Updated grid dims")
            allGridDomains[partnerNo]?.add(arrayOf(
                    domainDims[i*6+0], domainDims[i*6+1], domainDims[i*6+2], // starting position of domain
                    domainDims[i*6+3], domainDims[i*6+4], domainDims[i*6+5])) // end coordinate of domain
            logger.info("Updated domaindims")

        }
        lock.lock()
        numUpdated++
        lock.unlock()
    }

    override fun init() {
        windowHeight = 512
        windowWidth = 512

        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

//        volumeManager = VolumeManager(hub,
//                useCompute = true,
//                customSegments = hashMapOf(
//                        SegmentType.FragmentShader to SegmentTemplate(
//                                this.javaClass,
//                                "VDIGenerator.comp",
//                                "intersectBoundingBox", "vis", "SampleVolume", "Convert", "Accumulate"),
//                        SegmentType.Accumulator to SegmentTemplate(
//                                "AccumulateVDI.comp",
//                                "vis", "sampleVolume", "convert")
//                ))
//
//        val outputBuffer = MemoryUtil.memCalloc(windowWidth*windowHeight*4)
//        val outputTexture = Texture.fromImage(Image(outputBuffer, windowWidth, windowHeight), usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
//        outputTexture.mipmap = false
//        volumeManager.material.textures["OutputRender"] = outputTexture
//        val outputSubColorBuffer = MemoryUtil.memCalloc(windowHeight*windowWidth*4*maxSupersegments)
//        val outputSubDepthBuffer = MemoryUtil.memCalloc(windowHeight*windowWidth*4*maxSupersegments*2)
//        val outputSubVDIColor = Texture.fromImage(Image(outputSubColorBuffer, maxSupersegments, windowHeight, windowWidth), usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
//        volumeManager.material.textures["OutputSubVDIColor"] = outputSubVDIColor
//        val outputSubVDIDepth = Texture.fromImage(Image(outputSubDepthBuffer, 2*maxSupersegments, windowHeight, windowWidth), usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
//        volumeManager.material.textures["OutputSubVDIDepth"] = outputSubVDIDepth
//        hub.add(volumeManager)
//
//
//        compute.name = "compositor node"
//
//        compute.material = ShaderMaterial(Shaders.ShadersFromFiles(arrayOf("VDICompositor.comp"), this::class.java))
//        val outputColours = MemoryUtil.memCalloc(maxOutputSupersegments*windowHeight*windowWidth*4 / commSize)
//        val compositedVDIColor = Texture.fromImage(Image(outputColours, maxOutputSupersegments, windowHeight * windowWidth / commSize), usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
//        compute.material.textures["CompositedVDIColor"] = compositedVDIColor
//
//        val outputDepths = MemoryUtil.memCalloc(2*maxOutputSupersegments*windowHeight*windowWidth*4 / commSize)
//        val compositedVDIDepth = Texture.fromImage(Image(outputDepths, 2*maxOutputSupersegments, windowHeight * windowWidth / commSize), usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
//        compute.material.textures["CompositedVDIDepth"] = compositedVDIDepth
//
//        compute.metadata["ComputeMetadata"] = ComputeMetadata(
//                workSizes = Vector3i((windowWidth * windowHeight / commSize), 1, 1),
//        )
//        compute.visible = false
//        scene.addChild(compute)


//
//
        thread {
            while(renderer?.firstImageReady == false) {
                Thread.sleep(50)
            }

            renderer?.recordMovie("./RendererMovie$rank.mp4")
            Thread.sleep(100000)
            renderer?.recordMovie()
        }
//
//        val box = Box(Vector3f(0.0f, 2.0f, 1.0f))
//        box.name = "le box du win"
//        box.material.textures["diffuse"] = volumeManager.material.textures["OutputRender"]!!
//        box.material.metallic = 0.0f
//        box.material.roughness = 1.0f
//
//        scene.addChild(box)

//        val compute2 = Node()
//        compute2.name = "compute node 2"
//        compute2.material = ShaderMaterial(Shaders.ShadersFromFiles(arrayOf("SimpleVDIRenderer.comp"), this::class.java))
//        val renderedVDI = MemoryUtil.memCalloc(windowWidth * windowHeight * 4)
//        val outputTexture2 = Texture.fromImage(Image(renderedVDI, windowWidth, windowHeight), usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
//        outputTexture2.mipmap = false
//        compute2.material.textures["OutputViewport"] = outputTexture2
//        compute2.material.textures["InputColorVDI"] = volumeManager.material.textures["OutputSubVDIColor"]!!
//        compute2.material.textures["InputDepthVDI"] = volumeManager.material.textures["OutputSubVDIDepth"]!!
//        compute2.metadata["ComputeMetadata"] = ComputeMetadata(
//                workSizes = Vector3i(windowWidth, windowHeight, 1),
//        )
//
//        scene.addChild(compute2)

//        val compute2 = Node()
//        compute2.name = "compute node 2"
//        compute2.material = ShaderMaterial(Shaders.ShadersFromFiles(arrayOf("CompositedVDIRenderer.comp"), this::class.java))
//        val renderedVDI = MemoryUtil.memCalloc(windowWidth * windowHeight * 4)
//        val outputTexture2 = Texture.fromImage(Image(renderedVDI, windowWidth, windowHeight), usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
//        outputTexture2.mipmap = false
//        compute2.material.textures["OutputViewport"] = outputTexture2
//        compute2.material.textures["InputColorVDI"] = compute.material.textures["CompositedVDIColor"]!!
//        compute2.material.textures["InputDepthVDI"] = compute.material.textures["CompositedVDIDepth"]!!
//        compute2.metadata["ComputeMetadata"] = ComputeMetadata(
//                workSizes = Vector3i(windowWidth, windowHeight, 1),
//        )
//
//        scene.addChild(compute2)
//
//        val box2 = Box(Vector3f(4.0f, 2.0f, 1.0f))
//        box2.name = "le box du win"
//        box2.material.textures["diffuse"] = compute2.material.textures["OutputViewport"]!!
//        box2.material.metallic = 0.3f
//        box2.material.roughness = 0.9f
//
//        scene.addChild(box2)

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            position = Vector3f(-4.365f, 0.38f, 0.62f)
//            rotation = Quaternionf()
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
        var volumesInitialized = false
        thread {
            while(numUpdated != computePartners) {
                Thread.sleep(50)
            }

            logger.info("All compute partners have passed their grid data")

            for (partnerNo in 0 until computePartners) {
                val numGrids = numGridsPerPartner[partnerNo]!!
                val gridDims = allGridDims[partnerNo]!! // A list of dimensions of all the grids worked on by this computePartner
                val gridDomains = allGridDomains[partnerNo]!!
                val origins = allOrigins[partnerNo]!!

                for(grid in 0 until numGrids) {
                    volumeHashMaps[partnerNo]?.add(LinkedHashMap())
                    val width = gridDims[grid][3] - gridDims[grid][0] + 1
                    val height = gridDims[grid][4] - gridDims[grid][1] + 1
                    val depth = gridDims[grid][5] - gridDims[grid][2] + 1
                    val currentHasMap = volumeHashMaps[partnerNo]?.get(grid)!!
                    volumes[partnerNo]?.add( Volume.fromBuffer(currentHasMap, height.absoluteValue, width.absoluteValue, depth.absoluteValue, UnsignedShortType(), hub))
                    logger.info("width height and depth are $width $height $depth")
                    volumes[partnerNo]?.get(grid)?.name  = "Grid${grid}OfPartner${partnerNo}"

                    val pixelToWorld = 0.02f
                    origins[grid] = origins[grid].mul(pixelToWorld)

                    cam.position = origins[grid] + Vector3f(0.0f, 0.0f, 2.0f)

                    cam.rotation = Quaternionf(-2.323E-1 , 3.956E-1, -1.042E-1,  8.824E-1)

                    volumes[partnerNo]?.get(grid)?.position = origins[grid]
                    logger.info("Position of grid $grid of computePartner $partnerNo is ${volumes[partnerNo]?.get(grid)?.position}")
                    volumes[partnerNo]?.get(grid)?.origin = Origin.FrontBottomLeft
                    volumes[partnerNo]?.get(grid)?.needsUpdate = true
                    volumes[partnerNo]?.get(grid)?.colormap = Colormap.get("plasma")
                    volumes[partnerNo]?.get(grid)?.pixelToWorldRatio = pixelToWorld

                    val bg = BoundingGrid()
                    bg.node = volumes[partnerNo]?.get(grid)

                    with(volumes[partnerNo]?.get(grid)?.transferFunction) {
                        this?.addControlPoint(0.0f, 0.0f)
                        this?.addControlPoint(0.2f, 0.2f)
                        this?.addControlPoint(0.4f, 0.5f)
                        this?.addControlPoint(0.8f, 0.7f)
                        this?.addControlPoint(1.0f, 0.9f)
                    }

                    volumes[partnerNo]?.get(grid)?.metadata?.set("animating", true)
                    volumes[partnerNo]?.get(grid)?.let { scene.addChild(it)}

                    //TODO:Add the domain information to the volume
                }
            }
            volumesInitialized = true
            manageVDIGeneration()
        }

//        publishedNodes.add(cam)
//
//        val publisher = hub.get<NodePublisher>(SceneryElement.NodePublisher)
//        val subscriber = hub.get<NodeSubscriber>(SceneryElement.NodeSubscriber)
//
//        publishedNodes.forEachIndexed { index, node ->
//            publisher?.nodes?.put(13337 + index, node)
//
//            subscriber?.nodes?.put(13337 + index, node)
//        }

//        fixedRateTimer(initialDelay = 5, period = 5000) {
//            if (volumesInitialized && running && !shouldClose) {
//                updateVolumes()
//            }
//        }

        while(!volumesInitialized) {
            Thread.sleep(50)
        }
    }

    private fun manageVDIGeneration() {
        var subVDIDepthBuffer: ByteBuffer? = null
        var subVDIColorBuffer: ByteBuffer? = null

        var compositedVDIDepthBuffer: ByteBuffer? = null
        var compositedVDIColorBuffer: ByteBuffer? = null

        while(true) {
            updateVolumes()
            Thread.sleep(2000)
            //Start here

//            volumeManager.visible = true
//            // Get the rendered VDIs
//            // after that,
//            logger.info("Getting the rendered subVDIs")
//
//            val subVDIColor = volumeManager.material.textures["OutputSubVDIColor"]!!
//            val subVDIDepth = volumeManager.material.textures["OutputSubVDIDepth"]!!
//
//            val r = renderer
//
//            r?.requestTexture(subVDIColor) { colTex ->
//                logger.info("Fetched color VDI from GPU")
//
//                colTex.contents?.let{ colVDI ->
//                    subVDIColorBuffer = colVDI
//                }
//            }
//
//            r?.requestTexture(subVDIDepth) { depthTex ->
//
//                depthTex.contents?.let { depthVDI ->
//                    subVDIDepthBuffer = depthVDI
//                }
//            }
//
//            while(subVDIColorBuffer == null || subVDIDepthBuffer == null) {
//                Thread.sleep(5)
//            }
//
//            distributeVDIs(subVDIColorBuffer!!, subVDIDepthBuffer!!, windowHeight * windowWidth * maxSupersegments * 4, commSize)
//
//            logger.info("Back in the management function")
//
//            //fetch the composited VDI
//
//            val compositedColor = compute.material.textures["CompositedVDIColor"]!!
//            val compositedDepth = compute.material.textures["CompositedVDIDepth"]!!
//
//            r?.requestTexture(compositedColor) { colTex ->
//                logger.info("Fetched composited color VDI from GPU")
//                colTex.contents?.let{ compColVDI ->
//                    compositedVDIColorBuffer = compColVDI
//                }
//            }
//
//            r?.requestTexture(compositedDepth) { depthTex ->
//                depthTex.contents?.let { compDepthVDI ->
//                    compositedVDIDepthBuffer = compDepthVDI
////                    compute.visible = false
//                }
//            }
//
//            while(compositedVDIColorBuffer == null || compositedVDIDepthBuffer == null) {
//                Thread.sleep(5)
//            }
//
//            gatherCompositedVDIs(compositedVDIColorBuffer!!, compositedVDIDepthBuffer!!, 0, windowHeight * windowWidth * maxOutputSupersegments * 4, rank, commSize);

        }
    }

    fun updateVolumes() {
        logger.info("Updating volumes")
        for(partnerNo in 0 until computePartners) {
            val numGrids = numGridsPerPartner[partnerNo]!!
            val volumesFromThisPartner = volumes[partnerNo]!!
            val dataFromThisPartner = data[partnerNo]!!
            for(grid in 0 until numGrids) {
                dataFromThisPartner[grid]?.order(ByteOrder.nativeOrder())
                val buf = (dataFromThisPartner[grid] as ByteBuffer).asShortBuffer()
                logger.info("${buf.get(0).toUShort()}")
                logger.info("${buf.get(1).toUShort()}")
                logger.info("${buf.get(2).toUShort()}")
                logger.info("${buf.get(3).toUShort()}")
                logger.info("${buf.get(4).toUShort()}")

                logger.info("Updating grid $grid of compute partner $partnerNo")
                if(volumesFromThisPartner[grid]?.metadata?.get("animating") == true) {
                    logger.info("Grid data represented by bytebuffer with position ${dataFromThisPartner[grid]?.position()} and " +
                            "limit ${dataFromThisPartner[grid]?.limit()} and capacity ${dataFromThisPartner[grid]?.capacity()}")
                    volumesFromThisPartner[grid]?.addTimepoint("t-${count}", dataFromThisPartner[grid] as ByteBuffer) //TODO try with constant name
                    val currentHashMap = volumeHashMaps[partnerNo]?.get(grid)!!
                    logger.info("Going to timepoint ${currentHashMap.size-1}")
                    volumesFromThisPartner[grid]?.goToTimePoint(currentHashMap.size-1)
//                    volumesFromThisPartner[grid]?.purgeFirst(0, 1)
                }
                count++
            }
        }
    }

    @Suppress("unused")
    fun compositeVDIs(VDISetColour: ByteBuffer, VDISetDepth: ByteBuffer, sizePerProcess: Int) {
//        //Receive the VDIs and composite them
//
//        logger.info("In the composite function")
//
//        compute.material.textures["VDIsColor"] = Texture(Vector3i(maxSupersegments, windowHeight * windowWidth / commSize, commSize), 4, contents = VDISetColour, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
//        compute.material.textures["VDIsDepth"] = Texture(Vector3i(maxSupersegments*2, windowHeight * windowWidth / commSize, commSize), 4, contents = VDISetDepth, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
//        logger.info("Updated the ip textured")
//
//        compute.visible = true
//        logger.info("Set compute to visible")

//        var VDIs: Array<ByteBuffer?> = arrayOfNulls(commSize)
//
//        logger.info("In composite VDIs. The messages received are:")
//        for(i in 0 until commSize) {
//            VDISet.position(i * sizePerProcess)
//            VDIs[i] = VDISet.slice()
//            VDIs[i]?.limit((i+1) * sizePerProcess)
//            //VDI[i] is now one of the VDI fragments we need to composite
//            logger.info("Output of process $rank" + VDIs[i]?.asCharBuffer().toString())
//        }
    }

    @Test
    override fun main() {
        System.setProperty("scenery.Renderer.MaxVolumeCacheSize", "16")
//        System.setProperty("scenery.MasterNode", "tcp://127.0.0.1:6666")
//        System.setProperty("scenery.master", "false")
//        System.setProperty("scenery.Headless", "true")
        super.main()
    }
}