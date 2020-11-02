package graphics.scenery.insitu

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.backends.Shaders
import graphics.scenery.backends.vulkan.VulkanRenderer
import graphics.scenery.compute.ComputeMetadata
import graphics.scenery.textures.Texture
import graphics.scenery.utils.H264Encoder
import graphics.scenery.utils.Image
import graphics.scenery.utils.Statistics
import graphics.scenery.utils.SystemHelpers
import graphics.scenery.volumes.BufferedVolume
import graphics.scenery.volumes.Colormap
import graphics.scenery.volumes.Volume
import graphics.scenery.volumes.VolumeManager
import kotlinx.coroutines.runBlocking
import net.imglib2.type.numeric.integer.UnsignedShortType
import org.joml.Quaternionf
import org.joml.Vector3f
import org.joml.Vector3i
import org.junit.Test
import org.lwjgl.system.MemoryUtil
import org.msgpack.jackson.dataformat.MessagePackFactory
import org.scijava.ui.behaviour.ClickBehaviour
import tpietzsch.shadergen.generate.SegmentTemplate
import tpietzsch.shadergen.generate.SegmentType
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.math.absoluteValue

class DistributedVolumeRenderer: SceneryBase("DistributedVolumeRenderer") {

    // The below will be updated by the C++ code
    var windowSize = 500
    var computePartners = 0
    var rank = 0
    var commSize = 0

    var encoder: H264Encoder? = null
    var movieWriter: H264Encoder? = null
//    var movieWriter1: H264Encoder? = null
//    var movieWriter2: H264Encoder? = null
//    var movieWriter3: H264Encoder? = null
//
    var startRecording = false
    var stopRecording = false
    var recordingFinished = false
    val cam: Camera = DetachedHeadCamera()

    lateinit var data: Array<ArrayList<ByteBuffer?>?> // An array of the size computePartners, each element of which is a list of ShortBuffers, the individual grids of the compute partner
    lateinit var allOrigins: Array<ArrayList<Vector3f>?> // An array of size computePartners, each element of which is a list of 3D vectors, the origins of the individual grids of the compute partner
    lateinit var allGridDims: Array<ArrayList<Array<Int>>?>
    lateinit var allGridDomains: Array<ArrayList<Array<Int>>?>
    lateinit var numGridsPerPartner: Array<Int?>
//    lateinit var volumeHashMaps: Array<ArrayList<BufferedVolume.Timepoint?>?>
    lateinit var volumes: Array<ArrayList<BufferedVolume?>?>

    var numUpdated : Int = 0 // The number of partners that have updated data with pointers to their grids. Works only assuming no resizing on OpenFPM side
    val lock = ReentrantLock()
    var publishedNodes = ArrayList<Node>()
    val numSupersegments = 1

    lateinit var CompositedVDIColour: Texture
    //    lateinit var CompositedVDIDepth: Texture
    lateinit var volumeManager: VolumeManager


    var count = 0
    val compute = Box()
    val maxSupersegments = 1
    val maxOutputSupersegments = 1

    var saveFiles = false

    data class Timer(var start: Long, var end: Long)

    val tRend = Timer(0,0)
    val tComposite = Timer(0,0)
    val tDistr = Timer(0,0)
    val tGath = Timer(0,0)
    val tStream = Timer(0,0)
    val tTotal = Timer(0,0)
    val tGPU = Timer(0,0)

    var imgFetchTime: Long = 0
    var compositeTime: Long = 0
    var distrTime: Long = 0
    var gathTime: Long = 0
    var streamTime: Long = 0
    var totalTime: Long = 0
    var gpuSendTime: Long = 0
    var imgFetchPrev: Long = 0
    var compositePrev: Long = 0
    var distrPrev: Long = 0
    var gathPrev: Long = 0
    var streamPrev: Long = 0
    var totalPrev: Long = 0
    var gpuSendPrev: Long = 0

    var cnt = 0 //the loop counter

    private external fun distributeVDIs(subVDIColor: ByteBuffer, sizePerProcess: Int, commSize: Int)
    private external fun gatherCompositedVDIs(compositedVDIColor: ByteBuffer, root: Int, subVDILen: Int, myRank: Int, commSize: Int, saveFiles: Boolean)

    @Suppress("unused")
    fun initializeArrays() {
        data = arrayOfNulls(computePartners)
        numGridsPerPartner = arrayOfNulls(computePartners)
//        volumeHashMaps = arrayOfNulls(computePartners)
        volumes = arrayOfNulls(computePartners)
        allOrigins = arrayOfNulls(computePartners)
        allGridDims = arrayOfNulls(computePartners)
        allGridDomains = arrayOfNulls(computePartners)

        for(i in 0 until computePartners) {
            data[i] = ArrayList(0)
//            volumeHashMaps[i] = ArrayList(0)
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
        windowHeight = 600
        windowWidth = 600

        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        volumeManager = VolumeManager(hub,
                useCompute = true,
                customSegments = hashMapOf(
                        SegmentType.FragmentShader to SegmentTemplate(
                                this.javaClass,
                                "VDIGenerator.comp",
                                "intersectBoundingBox", "vis", "SampleVolume", "Convert", "Accumulate"),
                        SegmentType.Accumulator to SegmentTemplate(
//                                this.javaClass,
                                "AccumulateVDI.comp",
                                "vis", "sampleVolume", "convert")
                ))

//        val outputBuffer = MemoryUtil.memCalloc(windowWidth*windowHeight*4)
//        val outputTexture = Texture.fromImage(Image(outputBuffer, windowWidth, windowHeight), usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
//        outputTexture.mipmap = false
//        volumeManager.material.textures["OutputRender"] = outputTexture
        val outputSubColorBuffer = MemoryUtil.memCalloc(windowHeight*windowWidth*4*maxSupersegments*3)
//        val outputSubDepthBuffer = MemoryUtil.memCalloc(windowHeight*windowWidth*4*maxSupersegments*2)
        val outputSubVDIColor = Texture.fromImage(Image(outputSubColorBuffer, 3*maxSupersegments, windowHeight, windowWidth), usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
        volumeManager.material.textures["OutputSubVDIColor"] = outputSubVDIColor
//        val outputSubVDIDepth = Texture.fromImage(Image(outputSubDepthBuffer, 2*maxSupersegments, windowHeight, windowWidth), usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
//        volumeManager.material.textures["OutputSubVDIDepth"] = outputSubVDIDepth
        hub.add(volumeManager)


        compute.name = "compositor node"

        compute.material = ShaderMaterial(Shaders.ShadersFromFiles(arrayOf("PlainImageCompositor.comp"), this::class.java))
        val outputColours = MemoryUtil.memCalloc(windowHeight*windowWidth*4 / commSize)
        val alphaComposited = Texture.fromImage(Image(outputColours, windowHeight,  windowWidth/commSize), usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
        compute.material.textures["AlphaComposited"] = alphaComposited

        compute.metadata["ComputeMetadata"] = ComputeMetadata(
                workSizes = Vector3i(windowHeight, windowWidth/commSize, 1)
        )
        compute.visible = true
        scene.addChild(compute)

        with(cam) {
            position = Vector3f(-4.365f, 0.38f, 0.62f)
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
        shell.visible = false

        val lights = (0 until 3).map {
            PointLight(radius = 15.0f)
        }

        lights.mapIndexed { i, light ->
            light.position = Vector3f(2.0f * i - 4.0f,  i - 1.0f, 0.0f)
            light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
            light.intensity = 0.2f
            scene.addChild(light)
        }

//        settings.set("VideoEncoder.StreamVideo", false)
//        settings.set("VideoEncoder.StreamingAddress", "udp://${InetAddress.getLocalHost().hostAddress}:3337")

        encoder = H264Encoder(
                (windowWidth * settings.get<Float>("Renderer.SupersamplingFactor")).toInt(),
                (windowHeight* settings.get<Float>("Renderer.SupersamplingFactor")).toInt(),
                "udp://${InetAddress.getLocalHost().hostAddress}:3337",
                networked = true,
                streamingAddress = "udp://${InetAddress.getLocalHost().hostAddress}:3337",
                hub = hub)

        movieWriter = H264Encoder(
                (windowWidth * settings.get<Float>("Renderer.SupersamplingFactor")).toInt(),
                (windowHeight* settings.get<Float>("Renderer.SupersamplingFactor")).toInt(),
                "RenderMov.mp4",
                hub = hub,
                networked = false,
        )
//
//        movieWriter1 = H264Encoder(
//                (windowWidth * settings.get<Float>("Renderer.SupersamplingFactor")).toInt(),
//                (windowHeight* settings.get<Float>("Renderer.SupersamplingFactor")).toInt(),
//                "RenderMov_5Hours.mp4",
//                hub = hub,
//                networked = false,
//        )
//
//        movieWriter2 = H264Encoder(
//                (windowWidth * settings.get<Float>("Renderer.SupersamplingFactor")).toInt(),
//                (windowHeight* settings.get<Float>("Renderer.SupersamplingFactor")).toInt(),
//                "RenderMov_10Hours.mp4",
//                hub = hub,
//                networked = false,
//        )
//
//        movieWriter3 = H264Encoder(
//                (windowWidth * settings.get<Float>("Renderer.SupersamplingFactor")).toInt(),
//                (windowHeight* settings.get<Float>("Renderer.SupersamplingFactor")).toInt(),
//                "RenderMov_15Hours.mp4",
//                hub = hub,
//                networked = false,
//        )

//        fixedRateTimer("saving_files", initialDelay = 15000, period = 5000) {
//            logger.info("should write files now")
//            saveFiles = true
//        }

        // Create VolumeBuffer objects for each grid, configure them, and put them in the scene
        var volumesInitialized = false
        thread {
            while(numUpdated != computePartners) {
                Thread.sleep(50)
            }

            logger.info("All compute partners have passed their grid data")

            val colMap = Colormap.get("hot")

            for (partnerNo in 0 until computePartners) {
                val numGrids = numGridsPerPartner[partnerNo]!!
                val gridDims = allGridDims[partnerNo]!! // A list of dimensions of all the grids worked on by this computePartner
                val gridDomains = allGridDomains[partnerNo]!!
                val origins = allOrigins[partnerNo]!!

                for(grid in 0 until numGrids) {
//                    volumeHashMaps[partnerNo]?.add(emptyList())
                    val width = gridDims[grid][3] - gridDims[grid][0] + 1
                    val height = gridDims[grid][4] - gridDims[grid][1] + 1
                    val depth = gridDims[grid][5] - gridDims[grid][2] + 1
//                    val currentHasMap = volumeHashMaps[partnerNo]?.get(grid)!!
                    volumes[partnerNo]?.add( Volume.fromBuffer(emptyList(), width.absoluteValue, height.absoluteValue, depth.absoluteValue, UnsignedShortType(), hub))
                    logger.info("width height and depth are $width $height $depth")
                    volumes[partnerNo]?.get(grid)?.name  = "Grid${grid}OfPartner${partnerNo}"

                    val pixelToWorld = 0.02f
                    origins[grid] = origins[grid].mul(pixelToWorld)

                    cam.position = Vector3f(3.213f, 8.264E-1f, -9.844E-1f)

                    cam.rotation = Quaternionf(3.049E-2,  9.596E-1, -1.144E-1, -2.553E-1)

//                    cam.position = origins[grid] + Vector3f(0.0f, 0.0f, 2.0f)
//
//                    cam.rotation = Quaternionf(-2.323E-1 , 3.956E-1, -1.042E-1,  8.824E-1)

                    volumes[partnerNo]?.get(grid)?.position = origins[grid]
                    logger.info("Position of grid $grid of computePartner $partnerNo is ${volumes[partnerNo]?.get(grid)?.position}")
                    volumes[partnerNo]?.get(grid)?.origin = Origin.FrontBottomLeft
                    volumes[partnerNo]?.get(grid)?.needsUpdate = true
                    volumes[partnerNo]?.get(grid)?.colormap = colMap
                    volumes[partnerNo]?.get(grid)?.pixelToWorldRatio = pixelToWorld

                    val bg = BoundingGrid()
                    bg.node = volumes[partnerNo]?.get(grid)

                    with(volumes[partnerNo]?.get(grid)?.transferFunction) {
                        this?.addControlPoint(0.0f, 0.0f)
                        this?.addControlPoint(0.2f, 0.1f)
                        this?.addControlPoint(0.4f, 0.4f)
                        this?.addControlPoint(0.8f, 0.6f)
                        this?.addControlPoint(1.0f, 0.75f)
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

    private fun changeTransferFunction() {
        val colMap = Colormap.get("viridis")
        for (partnerNo in 0 until computePartners) {
            val numGrids = numGridsPerPartner[partnerNo]!!
            for(grid in 0 until numGrids) {
                volumes[partnerNo]?.get(grid)?.colormap = colMap

                with(volumes[partnerNo]?.get(grid)?.transferFunction) {
                    this?.addControlPoint(0.0f, 0.0f)
                    this?.addControlPoint(0.2f, 0.0f)
                    this?.addControlPoint(0.4f, 0.05f)
                    this?.addControlPoint(0.8f, 0.4f)
                    this?.addControlPoint(1.0f, 1.0f)
                }
            }
        }
    }

    override fun inputSetup() {
        super.inputSetup()

        inputHandler?.addBehaviour("save_textures", ClickBehaviour { _, _ ->
            logger.info("Activating saving of files!")
            saveFiles = true
        })
        inputHandler?.addKeyBinding("save_textures", "E")
    }

    private fun manageVDIGeneration() {
//        var subVDIDepthBuffer: ByteBuffer? = null
        var subVDIColorBuffer: ByteBuffer? = null
        var bufferToSend: ByteBuffer? = null

//        var compositedVDIDepthBuffer: ByteBuffer? = null
        var compositedVDIColorBuffer: ByteBuffer? = null

//        var imgFetchTime: Long = 0

        while(renderer?.firstImageReady == false) {
            Thread.sleep(50)
        }

        tGPU.start = System.nanoTime()
        updateVolumes()
        tGPU.end = System.nanoTime()

        gpuSendTime += tGPU.end - tGPU.start

        val r = renderer

        val subVDIColor = volumeManager.material.textures["OutputSubVDIColor"]!!

//        var reqRendered = r?.requestTexture(subVDIColor) { colTex ->
//            logger.info("Fetched color VDI from GPU")
//
//            colTex.contents?.let{ colVDI ->
//                subVDIColorBuffer = colVDI
//            }
//        }

        val compositedColor = compute.material.textures["AlphaComposited"]!!


        val composited = AtomicInteger(0)
        val subvdi = AtomicInteger(0)

        (renderer as? VulkanRenderer)?.postRenderLambdas?.add {
            subVDIColor to subvdi
        }

        (renderer as? VulkanRenderer)?.postRenderLambdas?.add {
            compositedColor to composited
        }

//        var reqComposited = r?.requestTexture(compositedColor) { colTex ->
//            logger.info("Fetched composited color VDI from GPU")
//            colTex.contents?.let{ compColVDI ->
//                compositedVDIColorBuffer = compColVDI
//            }
//        }


        var prevAtomic = composited.get()
        while(true) {
            tTotal.start = System.nanoTime()
            tRend.start = System.nanoTime()

            while(composited.get() == prevAtomic) {
                Thread.sleep(5)
            }

            logger.warn("Previous value was: $prevAtomic and the new value is ${composited.get()}")

            prevAtomic = composited.get()

            subVDIColorBuffer = subVDIColor.contents
            compositedVDIColorBuffer = compositedColor.contents

            //Start here

            logger.info("Getting the rendered subVDIs")

//            runBlocking {
//                reqRendered!!.await()
//            }

            bufferToSend = subVDIColorBuffer

//            if(saveFiles) {
//                logger.info("Dumping to file")
//                SystemHelpers.dumpToFile(subVDIColorBuffer!!, "$rank:textureSubCol-${SystemHelpers.formatDateTime(delimiter = "_")}.raw")
////                SystemHelpers.dumpToFile(subVDIDepthBuffer!!, "$rank:textureSubDepth-${SystemHelpers.formatDateTime(delimiter = "_")}.raw")
//                logger.info("File dumped")
//            }

//            volumeManager.visible = false

//            subVDIColorBuffer = null

//            reqRendered = r?.requestTexture(subVDIColor) { colTex ->
////                logger.info("Fetched color VDI from GPU")
//                colTex.contents?.let{ colVDI ->
//                    subVDIColorBuffer = colVDI
//                }
//            }

            tRend.end = System.nanoTime()
            if(cnt>0) {imgFetchTime += tRend.end - tRend.start}

            tDistr.start = System.nanoTime()
//            Thread.sleep(50)
            distributeVDIs(bufferToSend!!, windowHeight * windowWidth * maxSupersegments * 4 / commSize, commSize)

            logger.info("Back in the management function")

            //fetch the composited VDI

//            compute.visible = false
//            volumeManager.visible = true

//            runBlocking {
//                reqComposited!!.await()
//            }

//            if(saveFiles) {
//                logger.info("Dumping to file")
//                SystemHelpers.dumpToFile(compositedVDIColorBuffer!!, "$rank:textureCompCol-${SystemHelpers.formatDateTime(delimiter = "_")}.raw")
////                SystemHelpers.dumpToFile(compositedVDIDepthBuffer!!, "$rank:textureCompDepth-${SystemHelpers.formatDateTime(delimiter = "_")}.raw")
//                logger.info("File dumped")
//            }

            tComposite.end = System.nanoTime()
            if(cnt>0) {compositeTime += tComposite.end - tComposite.start}

            tGath.start = System.nanoTime()
//            Thread.sleep(20)
            gatherCompositedVDIs(compositedVDIColorBuffer!!,0, windowHeight * windowWidth * maxOutputSupersegments * 4 / (3 * commSize), rank, commSize, saveFiles) //3 * commSize because the supersegments here contain only 1 element

            tStream.end = System.nanoTime()
            if(cnt>0) {streamTime += tStream.end - tStream.start}

//            compositedVDIColorBuffer = null

//            reqComposited = r?.requestTexture(compositedColor) { colTex ->
////                logger.info("Fetched composited color VDI from GPU")
//                colTex.contents?.let{ compColVDI ->
//                    compositedVDIColorBuffer = compColVDI
//                }
//            }

            tTotal.end = System.nanoTime()
            if(cnt>0) {totalTime += tTotal.end - tTotal.start}

            if(rank == 0 && cnt!=0 && cnt%100 == 0) {
                //print the timer values
                logger.warn("Total vis time steps so far: $cnt. Printing vis timers now.")
                logger.warn((hub.get<Statistics>() as? Statistics)?.toString())
                logger.warn("Total time: $totalTime. Average is: ${(totalTime.toDouble()/cnt.toDouble())/1000000.0f}")
                logger.warn("Averaged over last 100, total time is: ${(totalTime-totalPrev)}. Average is: ${((totalTime-totalPrev).toDouble()/100.0)/1000000.0f}")
                totalPrev=totalTime
                logger.warn("Total communication time: ${distrTime + gathTime}. Average is: ${((distrTime + gathTime).toDouble()/cnt.toDouble())/1000000.0f}")
                logger.warn("Total all_to_all time: $distrTime. Average is: ${(distrTime.toDouble()/cnt.toDouble())/1000000.0f}")
                logger.warn("Averaged over last 100, all_to_all time is: ${(distrTime-distrPrev)}. Average is: ${((distrTime-distrPrev).toDouble()/100.0)/1000000.0f}")
                distrPrev=distrTime
                logger.warn("Total gather time: ${gathTime}. Average is: ${(gathTime.toDouble()/cnt.toDouble())/1000000.0f}")
                logger.warn("Averaged over last 100, gather time is: ${(gathTime-gathPrev)}. Average is: ${((gathTime-gathPrev).toDouble()/100.0)/1000000.0f}")
                gathPrev=gathTime
                logger.warn("Total streaming time: ${streamTime}. Average is: ${(streamTime.toDouble()/cnt.toDouble())/1000000.0f}")
                logger.warn("Averaged over last 100, streaming time is: ${(streamTime-streamPrev)}. Average is: ${((streamTime-streamPrev).toDouble()/100.0)/1000000.0f}")
                streamPrev=streamTime
                logger.warn("Total rendering (image fetch) time: $imgFetchTime. Average is: ${(imgFetchTime.toDouble()/cnt.toDouble())/1000000.0f}")
                logger.warn("Averaged over last 100, rendering (image fetch) time is: ${(imgFetchTime-imgFetchPrev)}. Average is: ${((imgFetchTime-imgFetchPrev).toDouble()/100.0)/1000000.0f}")
                imgFetchPrev=imgFetchTime
                logger.warn("Total compositing time: $compositeTime. Average is: ${(compositeTime.toDouble()/cnt.toDouble())/1000000.0f}")
                logger.warn("Averaged over last 100, compositing time is: ${(compositeTime-compositePrev)}. Average is: ${((compositeTime-compositePrev).toDouble()/100.0)/1000000.0f}")
                compositePrev=compositeTime
                logger.warn("Total GPU-send time: $gpuSendTime.")
                logger.warn("Averaged over last 100, total time is: ${(gpuSendTime-gpuSendPrev)}. Average is: ${((gpuSendTime-gpuSendPrev).toDouble()/100.0)/1000000.0f}")
                gpuSendPrev=gpuSendTime
            }

            cnt++

//            saveFiles = false
        }
    }

    fun updateVolumes() {
        logger.warn("Updating volumes")
        for(partnerNo in 0 until computePartners) {
            val numGrids = numGridsPerPartner[partnerNo]!!
            val volumesFromThisPartner = volumes[partnerNo]!!
            val dataFromThisPartner = data[partnerNo]!!
            for(grid in 0 until numGrids) {
                dataFromThisPartner[grid]?.order(ByteOrder.nativeOrder())
                val buf = (dataFromThisPartner[grid] as ByteBuffer).asShortBuffer()

                logger.debug("Updating grid $grid of compute partner $partnerNo")
                if(volumesFromThisPartner[grid]?.metadata?.get("animating") == true) {
                    logger.debug("Grid data represented by bytebuffer with position ${dataFromThisPartner[grid]?.position()} and " +
                            "limit ${dataFromThisPartner[grid]?.limit()} and capacity ${dataFromThisPartner[grid]?.capacity()}")
                    volumesFromThisPartner[grid]?.addTimepoint("t-${count}", dataFromThisPartner[grid] as ByteBuffer) //TODO try with constant name
                    volumesFromThisPartner[grid]?.goToLastTimepoint()
//                    val currentHashMap = volumeHashMaps[partnerNo]?.get(grid)!!
//                    logger.debug("Going to timepoint ${currentHashMap.size-1}")
//                    volumesFromThisPartner[grid]?.goToTimePoint(currentHashMap.size-1)
//                    volumesFromThisPartner[grid]?.purgeFirst(0, 1)
                }
                count++
            }
        }
        logger.warn("finished updating")
    }

    @Suppress("unused")
    fun compositeVDIs(VDISetColour: ByteBuffer, sizePerProcess: Int) {
        //Receive the VDIs and composite them

        tDistr.end = System.nanoTime()
        if(cnt>0) {distrTime += tDistr.end - tDistr.start}

        tComposite.start = System.nanoTime()

        logger.info("In the composite function")

        if(saveFiles) {
            logger.info("Dumping to file in the composite function")
            SystemHelpers.dumpToFile(VDISetColour, "$rank:textureVDISetCol-${SystemHelpers.formatDateTime(delimiter = "_")}.raw")
//            SystemHelpers.dumpToFile(VDISetDepth, "$rank:textureVDISetDepth-${SystemHelpers.formatDateTime(delimiter = "_")}.raw")
            logger.info("File dumped")
        }

        compute.material.textures["VDIsColor"] = Texture(Vector3i(maxSupersegments*3, windowHeight, windowWidth), 4, contents = VDISetColour, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
//        compute.material.textures["VDIsDepth"] = Texture(Vector3i(maxSupersegments*2, windowHeight, windowWidth), 4, contents = VDISetDepth, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
        logger.warn("Updated the textures to be composited")

//        compute.visible = true
        logger.info("Set compute to visible")

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
    
    @Suppress("unused")
    fun streamImage(image: ByteBuffer) {
        if(stopRecording) {
            if(!recordingFinished) {
                logger.info("Finishing the recording now!")
                encoder?.finish()
                movieWriter?.finish()
                recordingFinished = true
            }
        } else {
        tGath.end = System.nanoTime()
        if(cnt>0) {gathTime += (tGath.end - tGath.start)}

        tStream.start = System.nanoTime()
        encoder?.encodeFrame(image)
            if(startRecording) {
                movieWriter?.encodeFrame(image)
            }
        }
    }

    @Suppress("unused")
    fun updateVis(payloadBuffer: ByteBuffer) {
        logger.info("In updateVis function")
        val objectMapper = ObjectMapper(MessagePackFactory())

//        payloadBuffer.order(BIG_ENDIAN)
        val payload = ByteArray(payloadBuffer.capacity())
        payloadBuffer.get(payload)
        val deserialized: List<Any> = objectMapper.readValue(payload, object : TypeReference<List<Any>>() {})

        if(payload.size == 13) {
            logger.info("Ok, I will apply the change in transfer function")
            changeTransferFunction()
        } else if(payload.size == 16) {
            logger.info("Ok, I will stop the video recording")
            stopRecording = true
            logger.info("Trying to stop the recording")
        } else if(payload.size == 17) {
            logger.info("Ok, I will start the video recording")
            startRecording = true
        } else {
            logger.info("Done deserializing and now will apply it to the camera")
            cam.rotation = stringToQuaternion(deserialized[0].toString())
            cam.position = stringToVector3f(deserialized[1].toString())

            logger.info("The rotation is: ${cam.rotation}")
            logger.info("The position is: ${cam.position}")
        }
    }

    private fun stringToQuaternion(inputString: String): Quaternionf {
        val elements = inputString.removeSurrounding("[", "]").split(",").map { it.toFloat() }
        return Quaternionf(elements[0], elements[1], elements[2], elements[3])
    }

    private fun stringToVector3f(inputString: String): Vector3f {
        val mElements = inputString.removeSurrounding("[", "]").split(",").map { it.toFloat() }
        return Vector3f(mElements[0], mElements[1], mElements[2])
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