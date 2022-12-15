package graphics.scenery.insitu

import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.backends.Shaders
import graphics.scenery.backends.vulkan.VulkanRenderer
import graphics.scenery.backends.vulkan.VulkanTexture
import graphics.scenery.compute.ComputeMetadata
import graphics.scenery.controls.TrackedStereoGlasses
import graphics.scenery.textures.Texture
import graphics.scenery.utils.Image
import graphics.scenery.utils.SystemHelpers
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import graphics.scenery.volumes.*
import graphics.scenery.volumes.vdi.VDIBufferSizes
import graphics.scenery.volumes.vdi.VDIData
import graphics.scenery.volumes.vdi.VDIDataIO
import graphics.scenery.volumes.vdi.VDIMetadata
import net.imglib2.type.numeric.integer.IntType
import net.imglib2.type.numeric.integer.UnsignedByteType
import net.imglib2.type.numeric.integer.UnsignedShortType
import net.imglib2.type.numeric.real.FloatType
import org.joml.*
import org.lwjgl.system.MemoryUtil
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.Math
import java.nio.ByteBuffer
import java.nio.IntBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.streams.toList
import kotlin.system.measureNanoTime

class CompositorNode : RichNode() {
    @ShaderProperty
    var ProjectionOriginal = Matrix4f()

    @ShaderProperty
    var invProjectionOriginal = Matrix4f()

    @ShaderProperty
    var ViewOriginal = Matrix4f()

    @ShaderProperty
    var invViewOriginal = Matrix4f()

    @ShaderProperty
    var nw = 0f

    @ShaderProperty
    var doComposite = false

    @ShaderProperty
    var numProcesses = 0
}

class DistributedVolumes: SceneryBase("DistributedVolumeRenderer", windowWidth = 1280, windowHeight = 720, wantREPL = false) {

    private val vulkanProjectionFix =
        Matrix4f(
            1.0f,  0.0f, 0.0f, 0.0f,
            0.0f, -1.0f, 0.0f, 0.0f,
            0.0f,  0.0f, 0.5f, 0.0f,
            0.0f,  0.0f, 0.5f, 1.0f)

    fun Matrix4f.applyVulkanCoordinateSystem(): Matrix4f {
        val m = Matrix4f(vulkanProjectionFix)
        m.mul(this)

        return m
    }

    var volumes: HashMap<Int, BufferedVolume?> = java.util.HashMap()

    var hmd: TrackedStereoGlasses? = null

    lateinit var volumeManager: VolumeManager
    lateinit var volumeCommons: VolumeCommons
    val compositor = CompositorNode()

    val generateVDIs = true
    val denseVDIs = true
    val separateDepth = true
    val colors32bit = true
    val saveFinal = true
    val benchmarking = false
    var cnt_distr = 0
    var cnt_sub = 0
    var vdisGathered = 0
    val cam: Camera = DetachedHeadCamera(hmd)
    var camTarget = Vector3f(0f)

    val maxSupersegments = 20
    var maxOutputSupersegments = 20

    var commSize = 1
    var rank = 0
    var nodeRank = 0
    var pixelToWorld = 0.001f
    var volumeDims = Vector3f(0f)
    var dataset = ""
    var isCluster = false
    var basePath = ""
    var rendererConfigured = false

    var mpiPointer = 0L
    var allToAllColorPointer = 0L
    var allToAllDepthPointer = 0L
    var allToAllPrefixPointer = 0L
    var gatherColorPointer = 0L
    var gatherDepthPointer = 0L

    var volumesCreated = false
    var volumeManagerInitialized = false

    var vdisGenerated = AtomicInteger(0)
    var vdisDistributed = AtomicInteger(0)
    var vdisComposited = AtomicInteger(0)

    @Volatile
    var runGeneration = true
    @Volatile
    var runThreshSearch = true
    @Volatile
    var runCompositing = false

    val singleGPUBenchmarks = false
    val colorMap = Colormap.get("hot")

    var dims = Vector3i(0)

    private external fun distributeVDIs(subVDIColor: ByteBuffer, subVDIDepth: ByteBuffer, sizePerProcess: Int, commSize: Int,
        colPointer: Long, depthPointer: Long, mpiPointer: Long)
    private external fun distributeDenseVDIs(subVDIColor: ByteBuffer, subVDIDepth: ByteBuffer, prefixSums: ByteBuffer, colorLimits: IntArray, depthLimits: IntArray, commSize: Int,
                                        colPointer: Long, depthPointer: Long, prefixPointer: Long, mpiPointer: Long)
    private external fun gatherCompositedVDIs(compositedVDIColor: ByteBuffer, compositedVDIDepth: ByteBuffer, compositedVDILen: Int, root: Int, myRank: Int, commSize: Int,
        colPointer: Long, depthPointer: Long, mpiPointer: Long)

    @Suppress("unused")
    fun setVolumeDims(dims: IntArray) {
        volumeDims = Vector3f(dims[0].toFloat(), dims[1].toFloat(), dims[2].toFloat())
    }

    @Suppress("unused")
    fun addVolume(volumeID: Int, dimensions: IntArray, pos: FloatArray, is16bit: Boolean) {
        logger.info("Trying to add the volume")
        logger.info("id: $volumeID, dims: ${dimensions[0]}, ${dimensions[1]}, ${dimensions[2]} pos: ${pos[0]}, ${pos[1]}, ${pos[2]}")

        while(!volumeManagerInitialized) {
            Thread.sleep(50)
        }

        val volume = if(is16bit) {
            Volume.fromBuffer(emptyList(), dimensions[0], dimensions[1], dimensions[2], UnsignedShortType(), hub)
        } else {
            Volume.fromBuffer(emptyList(), dimensions[0], dimensions[1], dimensions[2], UnsignedByteType(), hub)
        }
        volume.spatial().position = Vector3f(pos[0], pos[1], pos[2])

        volume.origin = Origin.FrontBottomLeft
        volume.spatial().needsUpdate = true
        volume.colormap = colorMap
        volume.pixelToWorldRatio = pixelToWorld

        val tf = volumeCommons.setupTransferFunction()

        volume.transferFunction = tf

        if(dataset.contains("BonePlug")) {
            volume.converterSetups[0].setDisplayRange(200.0, 12500.0)
            volume.colormap = Colormap.get("viridis")
        }

        if(dataset == "Rotstrat") {
            volume.colormap = Colormap.get("jet")
            volume.converterSetups[0].setDisplayRange(25000.0, 50000.0)
        }

        scene.addChild(volume)

        volumes[volumeID] = volume


        volumesCreated = true
    }

    @Suppress("unused")
    fun updateVolume(volumeID: Int, buffer: ByteBuffer) {
        while(volumes[volumeID] == null) {
            Thread.sleep(50)
        }
        logger.info("Volume $volumeID has been updated")
        volumes[volumeID]?.addTimepoint("t", buffer)
        volumes[volumeID]?.goToLastTimepoint()
    }

    fun setupCompositor(compositeShader: String) {

        compositor.name = "compositor node"
        compositor.setMaterial(ShaderMaterial(Shaders.ShadersFromFiles(arrayOf(compositeShader), this@DistributedVolumes::class.java)))
        if(generateVDIs) {
            val outputColours = MemoryUtil.memCalloc(maxOutputSupersegments*windowHeight*windowWidth*4*4 / commSize)
            val outputDepths = MemoryUtil.memCalloc(maxOutputSupersegments*windowHeight*windowWidth*4*2 / commSize)
            val compositedVDIColor = Texture.fromImage(Image(outputColours, maxOutputSupersegments, windowHeight,  windowWidth/commSize), channels = 4, usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture),
                type = FloatType(), mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)
            val compositedVDIDepth = Texture.fromImage(Image(outputDepths, 2 * maxOutputSupersegments, windowHeight,  windowWidth/commSize), channels = 1, usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture)
                , type = FloatType(), mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)
            compositor.material().textures["CompositedVDIColor"] = compositedVDIColor
            compositor.material().textures["CompositedVDIDepth"] = compositedVDIDepth
        } else {
            val outputColours = MemoryUtil.memCalloc(windowHeight*windowWidth*4 / commSize)
            val alphaComposited = Texture.fromImage(Image(outputColours, windowHeight,  windowWidth/commSize), usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
            compositor.material().textures["AlphaComposited"] = alphaComposited
        }
        compositor.metadata["ComputeMetadata"] = ComputeMetadata(
            workSizes = Vector3i(windowWidth/commSize, windowHeight, 1)
        )
        if(!singleGPUBenchmarks) {
            compositor.visible = true
            scene.addChild(compositor)
        } else {
            compositor.visible = false
        }
    }

    override fun init() {

        logger.info("setting renderer device id to: $nodeRank")
        System.setProperty("scenery.Renderer.DeviceId", nodeRank.toString())

        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        if(generateVDIs) {
            //set up volume manager

            volumeManager = VDIVolumeManager.create(windowWidth, windowHeight, maxSupersegments, scene, hub, denseVDIs)

            hub.add(volumeManager)

            if(denseVDIs) {
                runThreshSearch = true
                runGeneration = false

            } else {
                runGeneration = true
                setupCompositor("VDICompositor.comp")
            }

        } else {
            volumeManager = VDIVolumeManager.create(windowWidth, windowHeight, scene, hub)

            hub.add(volumeManager)
        }

        volumeManagerInitialized = true

        volumeCommons = VolumeCommons(windowWidth, windowHeight, dataset, logger)

        volumeCommons.positionCamera(cam)
        scene.addChild(cam)

        val lights = (0 until 3).map {
            PointLight(radius = 15.0f)
        }

        lights.mapIndexed { i, light ->
            light.spatial().position = Vector3f(2.0f * i - 4.0f,  i - 1.0f, 0.0f)
            light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
            light.intensity = 0.5f
            scene.addChild(light)
        }

        while(!rendererConfigured) {
            Thread.sleep(50)
        }

        logger.info("Exiting init function!")

        basePath = if(isCluster) {
            "/beegfs/ws/1/argupta-vdi_generation/vdi_dumps/"
        } else {
            "/home/aryaman/TestingData/"
        }

        thread {
            if(generateVDIs) {
                if(singleGPUBenchmarks) {
                    doBenchmarks()
                } else {
                    if(denseVDIs) {
                        manageDenseVDIs()
                    } else {
                        manageVDIGeneration()
                    }
                }

            } else {
                saveScreenshots()
            }
        }
    }

    fun doBenchmarks() {
        while(renderer?.firstImageReady == false) {
            Thread.sleep(50)
        }

        while(!rendererConfigured) {
            Thread.sleep(50)
        }

        val pivot = Box(Vector3f(20.0f))
        pivot.material().diffuse = Vector3f(0.0f, 1.0f, 0.0f)
        pivot.spatial().position = Vector3f(volumeDims.x/2.0f, volumeDims.y/2.0f, volumeDims.z/2.0f)
//        parent.children.first().addChild(pivot)
        volumes[0]?.addChild(pivot)
//        parent.spatial().updateWorld(true)
        volumes[0]?.spatial()?.updateWorld(true)

        cam.target = pivot.spatial().worldPosition(Vector3f(0.0f))
        camTarget = pivot.spatial().worldPosition(Vector3f(0.0f))

        pivot.visible = false

        logger.info("Setting target to: ${pivot.spatial().worldPosition(Vector3f(0.0f))}")

        val model = volumes[0]?.spatial()?.world

        val vdiData = VDIData(
            VDIBufferSizes(),
            VDIMetadata(
                projection = cam.spatial().projection,
                view = cam.spatial().getTransformation(),
                volumeDimensions = volumeDims,
                model = model!!,
                nw = volumes[0]?.volumeManager?.shaderProperties?.get("nw") as Float,
                windowDimensions = Vector2i(cam.width, cam.height)
            )
        )

        var subVDIDepthBuffer: ByteBuffer? = null
        var subVDIColorBuffer: ByteBuffer?

        var numGenerated = 0

        val subVDIColor = volumeManager.material().textures["OutputSubVDIColor"]!!
        val colorCnt = AtomicInteger(0)

        (renderer as? VulkanRenderer)?.persistentTextureRequests?.add (subVDIColor to colorCnt)

        val depthCnt = AtomicInteger(0)
        var subVDIDepth: Texture? = null

        if(separateDepth) {
            subVDIDepth = volumeManager.material().textures["OutputSubVDIDepth"]!!
            (renderer as? VulkanRenderer)?.persistentTextureRequests?.add (subVDIDepth to depthCnt)
        }

        (renderer as VulkanRenderer).postRenderLambdas.add {
            vdiData.metadata.projection = cam.spatial().projection
            vdiData.metadata.view = cam.spatial().getTransformation()

            numGenerated++

            if(numGenerated == 10) {
                stats.clear("Renderer.fps")
            }

            if(numGenerated > 10) {
                rotateCamera(5f)
            }

            if(numGenerated > 155) {
//            if(numGenerated > 75) {
                //Stop
                val fps = stats.get("Renderer.fps")!!
                File("${dataset}_${windowWidth}_${windowHeight}_$maxSupersegments.csv").writeText("${fps.avg()};${fps.min()};${fps.max()};${fps.stddev()};${fps.data.size}")
                renderer?.shouldClose = true
            }

//            if(numGenerated % 20 == 0) {
//
//                subVDIColorBuffer = subVDIColor.contents
//                if (subVDIDepth != null) {
//                    subVDIDepthBuffer = subVDIDepth.contents
//                }
//
//                val fileName = "${dataset}VDI${numGenerated}_ndc"
//                SystemHelpers.dumpToFile(subVDIColorBuffer!!, "${fileName}_col")
//                SystemHelpers.dumpToFile(subVDIDepthBuffer!!, "${fileName}_depth")
//
//                val file = FileOutputStream(File("${dataset}vdidump$numGenerated"))
//                VDIDataIO.write(vdiData, file)
//                logger.info("written the dump")
//                file.close()
//            }
        }

    }

    private fun rotateCamera(degrees: Float) {
        cam.targeted = true
        val frameYaw = degrees / 180.0f * Math.PI.toFloat()
        val framePitch = 0f

        // first calculate the total rotation quaternion to be applied to the camera
        val yawQ = Quaternionf().rotateXYZ(0.0f, frameYaw, 0.0f).normalize()
        val pitchQ = Quaternionf().rotateXYZ(framePitch, 0.0f, 0.0f).normalize()

//        logger.info("Applying the rotation! camtarget is: $camTarget")

        val distance = (camTarget - cam.spatial().position).length()
        cam.spatial().rotation = pitchQ.mul(cam.spatial().rotation).mul(yawQ).normalize()
        cam.spatial().position = camTarget + cam.forward * distance * (-1.0f)
    }

    private fun saveScreenshots() {
        val r = (hub.get(SceneryElement.Renderer) as Renderer)

        while(!r.firstImageReady) {
            Thread.sleep(200)
        }

        val numScreenshots = 5

        dataset += "_${commSize}_${rank}"

        for(i in 1..numScreenshots) {
            val path = basePath + "${dataset}Screenshot$i"

            r.screenshot("$path.png")
            Thread.sleep(5000L)
        }
    }


    @Suppress("unused")
    fun stopRendering() {
        renderer?.shouldClose = true
    }

    fun fetchTexture(texture: Texture) : Int {
        val ref = VulkanTexture.getReference(texture)
        val buffer = texture.contents ?: return -1

        if(ref != null) {
            val start = System.nanoTime()
//            texture.contents = ref.copyTo(buffer, true)
            ref.copyTo(buffer, false)
            val end = System.nanoTime()
//            logger.info("The request textures of size ${texture.contents?.remaining()?.toFloat()?.div((1024f*1024f))} took: ${(end.toDouble()-start.toDouble())/1000000.0}")
        } else {
            logger.error("In fetchTexture: Texture not accessible")
        }

        return 0
    }

    private fun setupMetadata(): VDIData {
        basePath = if(isCluster) {
            "/beegfs/ws/1/argupta-vdi_generation/vdi_dumps/"
        } else {
            "/home/aryaman/TestingData/"
        }

        camTarget = when (dataset) {
            "Kingsnake" -> {
                Vector3f(1.920E+0f, -1.920E+0f,  1.491E+0f)
            }
            "Beechnut" -> {
                Vector3f(1.920E+0f, -1.920E+0f,  2.899E+0f)
            }
            "Simulation" -> {
                Vector3f(1.920E+0f, -1.920E+0f,  1.800E+0f)
            }
            "Rayleigh_Taylor" -> {
                Vector3f(1.920E+0f, -1.920E+0f,  1.920E+0f)
            }
            "BonePlug" -> {
                Vector3f(1.920E+0f, -6.986E-1f,  6.855E-1f)
            }
            "Rotstrat" -> {
                Vector3f( 1.920E+0f, -1.920E+0f,  1.800E+0f)
            }
            else -> {
                Vector3f(0f)
            }
        }

        val model = volumes[0]?.spatial()?.world

        val vdiData = VDIData(
            VDIBufferSizes(),
            VDIMetadata(
                projection = cam.spatial().projection,
                view = cam.spatial().getTransformation(),
                volumeDimensions = volumeDims,
                model = model!!,
                nw = volumes[0]?.volumeManager?.shaderProperties?.get("nw") as Float,
                windowDimensions = Vector2i(cam.width, cam.height)
            )
        )

        return vdiData
    }

    private fun manageDenseVDIs() {
        while(renderer?.firstImageReady == false) {
            Thread.sleep(50)
        }

        while(!rendererConfigured) {
            Thread.sleep(50)
        }

        val vdiData = setupMetadata()

        compositor.nw = vdiData.metadata.nw
        compositor.ViewOriginal = vdiData.metadata.view
        compositor.invViewOriginal = Matrix4f(vdiData.metadata.view).invert()
        compositor.ProjectionOriginal = Matrix4f(vdiData.metadata.projection).applyVulkanCoordinateSystem()
        compositor.invProjectionOriginal = Matrix4f(vdiData.metadata.projection).applyVulkanCoordinateSystem().invert()
        compositor.numProcesses = commSize

        var colorTexture = volumes[0]?.volumeManager?.material()?.textures?.get("OutputSubVDIColor")!!
        var depthTexture = volumes[0]?.volumeManager?.material()?.textures?.get("OutputSubVDIDepth")!!
        val numGeneratedTexture = volumeManager.material().textures["SupersegmentsGenerated"]!!

//        var compositedColor = compositor.material().textures["CompositedVDIColor"]!!
//        var compositedDepth = compositor.material().textures["CompositedVDIDepth"]!!

        var prefixBuffer: ByteBuffer? = null
        var prefixIntBuff: IntBuffer? = null
        var totalSupersegmentsGenerated = 0

        (renderer as VulkanRenderer).postRenderLambdas.add {
            if(runThreshSearch) {
                //fetch numgenerated, calculate and upload prefixsums

                val generated = fetchTexture(numGeneratedTexture)

                if(generated < 0) {
                    logger.error("Error fetching texture. generated: $generated")
                }

                val numGeneratedBuff = numGeneratedTexture.contents
                val numGeneratedIntBuffer = numGeneratedBuff!!.asIntBuffer()

                val prefixTime = measureNanoTime {
                    prefixBuffer = MemoryUtil.memAlloc(windowWidth * windowHeight * 4)
                    prefixIntBuff = prefixBuffer!!.asIntBuffer()

                    prefixIntBuff!!.put(0, 0)

                    for(i in 1 until windowWidth * windowHeight) {
                        prefixIntBuff!!.put(i, prefixIntBuff!!.get(i-1) + numGeneratedIntBuffer.get(i-1))
                    }

                    totalSupersegmentsGenerated = prefixIntBuff!!.get(windowWidth*windowHeight-1) + numGeneratedIntBuffer.get(windowWidth*windowHeight-1)
                }
                logger.info("Prefix sum took ${prefixTime/1e9} to compute")


                volumes[0]!!.volumeManager.material().textures["PrefixSums"] = Texture(
                    Vector3i(windowWidth, windowHeight, 1), 1, contents = prefixBuffer, usageType = hashSetOf(
                        Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture)
                    , type = IntType(),
                    mipmap = false,
                    minFilter = Texture.FilteringMode.NearestNeighbour,
                    maxFilter = Texture.FilteringMode.NearestNeighbour
                )

                runThreshSearch = false
                runGeneration = true
            } else if(runGeneration) {

                colorTexture = volumes[0]?.volumeManager?.material()?.textures?.get("OutputSubVDIColor")!!
                depthTexture = volumes[0]?.volumeManager?.material()?.textures?.get("OutputSubVDIDepth")!!

                val col = fetchTexture(colorTexture)
                val depth = fetchTexture(depthTexture)

                if(col < 0) {
                    logger.error("Error fetching the color subVDI!!")
                }
                if(depth < 0) {
                    logger.error("Error fetching the depth subVDI!!")
                }

                vdisGenerated.incrementAndGet()
                runGeneration = false
            }

            if(runCompositing) {
//                compositedColor = compositor.material().textures["CompositedVDIColor"]!!
//                compositedDepth = compositor.material().textures["CompositedVDIDepth"]!!
//
//                val col = fetchTexture(compositedColor)
//                val depth = fetchTexture(compositedDepth)
//
//                if(col < 0) {
//                    logger.error("Error fetching the color compositedVDI!!")
//                }
//                if(depth < 0) {
//                    logger.error("Error fetching the depth compositedVDI!!")
//                }

                vdisComposited.incrementAndGet()
                runCompositing = false

                runThreshSearch = true
            }

            if(vdisDistributed.get() > vdisComposited.get()) {
                runCompositing = true
            }
        }

        (renderer as VulkanRenderer).postRenderLambdas.add {
            compositor.doComposite = runCompositing

            volumes[0]?.volumeManager?.shaderProperties?.set("doGeneration", runGeneration)
            volumes[0]?.volumeManager?.shaderProperties?.set("doThreshSearch", runThreshSearch)
        }

        var generatedSoFar = 0
        var compositedSoFar = 0

        dataset += "_${commSize}_${rank}"

        var start = 0L
        var end = 0L

        var start_complete = 0L
        var end_complete = 0L

        while(true) {

            start_complete = System.nanoTime()

            var subVDIDepthBuffer: ByteBuffer? = null
            var subVDIColorBuffer: ByteBuffer?
            var bufferToSend: ByteBuffer? = null

            var compositedVDIDepthBuffer: ByteBuffer?
            var compositedVDIColorBuffer: ByteBuffer?

            start = System.nanoTime()
            while((vdisGenerated.get() <= generatedSoFar)) {
                Thread.sleep(5)
            }
            end = System.nanoTime() - start

//            logger.info("Waiting for VDI generation took: ${end/1e9}")

            generatedSoFar = vdisGenerated.get()

            subVDIColorBuffer = colorTexture.contents
            subVDIDepthBuffer = depthTexture.contents

            subVDIColorBuffer!!.limit(totalSupersegmentsGenerated * 4 * 4)
            subVDIDepthBuffer!!.limit(2 * totalSupersegmentsGenerated * 4)

//            compositedVDIColorBuffer = compositedColor.contents
//            compositedVDIDepthBuffer = compositedDepth.contents


//            if(!benchmarking) {
            logger.info("Dumping sub VDI files")
            SystemHelpers.dumpToFile(subVDIColorBuffer!!, basePath + "${dataset}SubVDI_${windowWidth}_${windowHeight}_${maxSupersegments}_0_${cnt_sub}_ndc_col_rle")
            SystemHelpers.dumpToFile(subVDIDepthBuffer!!, basePath + "${dataset}SubVDI_${windowWidth}_${windowHeight}_${maxSupersegments}_0_${cnt_sub}_ndc_depth_rle")
            SystemHelpers.dumpToFile(prefixBuffer!!, basePath + "${dataset}SubVDI_${windowWidth}_${windowHeight}_${maxSupersegments}_0_${cnt_sub}_ndc_prefix")
            logger.info("File dumped")
            cnt_sub++

//            }
            val colorLimits = IntArray(commSize)
            val depthLimits = IntArray(commSize)

            val totalLists = windowHeight * windowWidth

            for(i in 0 until (commSize-1)) {
                colorLimits[i] = prefixIntBuff!!.get((totalLists / commSize) * (i + 1) + 1) * 4 * 4
                depthLimits[i] = prefixIntBuff!!.get((totalLists / commSize) * (i + 1) + 1) * 4 * 2
            }
            colorLimits[commSize-1] = totalSupersegmentsGenerated * 4 * 4
            depthLimits[commSize-1] = totalSupersegmentsGenerated * 4 * 2

            logger.info("Total supersegments generated by rank $rank: $totalSupersegmentsGenerated")

            start = System.nanoTime()
            distributeDenseVDIs(subVDIColorBuffer!!, subVDIDepthBuffer!!, prefixBuffer!!, colorLimits, depthLimits, commSize, allToAllColorPointer,
                allToAllDepthPointer, allToAllPrefixPointer, mpiPointer)
            end = System.nanoTime() - start

//            logger.info("Distributing VDIs took: ${end/1e9}")
            logger.info("Back in the management function")

            start = System.nanoTime()
            while(vdisComposited.get() <= compositedSoFar) {
                Thread.sleep(5)
            }
            end = System.nanoTime() - start

            compositedSoFar = vdisComposited.get()

            //fetch the composited VDI

//            if(!benchmarking) {
//                logger.info("Dumping sub VDI files")
//                SystemHelpers.dumpToFile(compositedVDIColorBuffer!!, basePath + "${dataset}CompositedVDI${cnt_sub}_ndc_col")
//                SystemHelpers.dumpToFile(compositedVDIDepthBuffer!!, basePath + "${dataset}CompositedVDI${cnt_sub}_ndc_depth")
//                logger.info("File dumped")
//
//                logger.info("Cam position: ${cam.spatial().position}")
//                logger.info("Cam rotation: ${cam.spatial().rotation}")
//
//                cnt_sub++
//            }
//
//            start = System.nanoTime()
//            gatherCompositedVDIs(compositedVDIColorBuffer!!, compositedVDIDepthBuffer!!, windowHeight * windowWidth * maxOutputSupersegments * 4 / commSize, 0,
//                rank, commSize, gatherColorPointer, gatherDepthPointer, mpiPointer) //3 * commSize because the supersegments here contain only 1 element
//            end = System.nanoTime() - start
//
////            logger.info("Gather took: ${end/1e9}")


            if(saveFinal && (rank == 0)) {
                val file = FileOutputStream(File(basePath + "${dataset}vdi_${windowWidth}_${windowHeight}_${maxSupersegments}_0_dump$vdisGathered"))
                VDIDataIO.write(vdiData, file)
                logger.info("written the dump $vdisGathered")
                file.close()
            }

            vdisGathered++
            end_complete = System.nanoTime() - start_complete
//            logger.info("Whole iteration took: ${end_complete/1e9}")
        }

    }

    private fun manageVDIGeneration() {

        while(renderer?.firstImageReady == false) {
            Thread.sleep(50)
        }

        while(!rendererConfigured) {
            Thread.sleep(50)
        }

        val vdiData = setupMetadata()

        compositor.nw = vdiData.metadata.nw
        compositor.ViewOriginal = vdiData.metadata.view
        compositor.invViewOriginal = Matrix4f(vdiData.metadata.view).invert()
        compositor.ProjectionOriginal = Matrix4f(vdiData.metadata.projection).applyVulkanCoordinateSystem()
        compositor.invProjectionOriginal = Matrix4f(vdiData.metadata.projection).applyVulkanCoordinateSystem().invert()
        compositor.numProcesses = commSize

        var colorTexture = volumeManager.material().textures["OutputSubVDIColor"]!!
        var depthTexture = volumeManager.material().textures["OutputSubVDIDepth"]!!

        var compositedColor =
            if(generateVDIs) {
                compositor.material().textures["CompositedVDIColor"]!!
            } else {
                compositor.material().textures["AlphaComposited"]!!
            }
        var compositedDepth = compositor.material().textures["CompositedVDIDepth"]!!

        (renderer as VulkanRenderer).postRenderLambdas.add {
            if(runGeneration) {

                colorTexture = volumeManager.material().textures["OutputSubVDIColor"]!!
                depthTexture = volumeManager.material().textures["OutputSubVDIDepth"]!!

                val col = fetchTexture(colorTexture)
                val depth = if(separateDepth) {
                    fetchTexture(depthTexture)
                } else {
                    0
                }

                if(col < 0) {
                    logger.error("Error fetching the color subVDI!!")
                }
                if(depth < 0) {
                    logger.error("Error fetching the depth subVDI!!")
                }

                vdisGenerated.incrementAndGet()
                runGeneration = false
            }
            if(runCompositing) {
                compositedColor = compositor.material().textures["CompositedVDIColor"]!!
                compositedDepth = compositor.material().textures["CompositedVDIDepth"]!!

                val col = fetchTexture(compositedColor)
                val depth = if(separateDepth) {
                    fetchTexture(compositedDepth)
                } else {
                    0
                }

                if(col < 0) {
                    logger.error("Error fetching the color compositedVDI!!")
                }
                if(depth < 0) {
                    logger.error("Error fetching the depth compositedVDI!!")
                }

                vdisComposited.incrementAndGet()
                runCompositing = false

                logger.info("The camera is not rotating!")
//                rotateCamera(10f)
//                vdiData.metadata.projection = cam.spatial().projection
//                vdiData.metadata.view = cam.spatial().getTransformation()
                runGeneration = true
            }

            if(vdisDistributed.get() > vdisComposited.get()) {
                runCompositing = true
            }
        }

        (renderer as VulkanRenderer).postRenderLambdas.add {
            if(runCompositing) {
//                logger.info("SETTING DO_COMPOSITE TO TRUE!")
            }
            compositor.doComposite = runCompositing
            volumes[0]?.volumeManager?.shaderProperties?.set("doGeneration", runGeneration)
        }

        var generatedSoFar = 0
        var compositedSoFar = 0

        dataset += "_${commSize}_${rank}"

        var start = 0L
        var end = 0L

        var start_complete = 0L
        var end_complete = 0L

        while(true) {

            start_complete = System.nanoTime()

            var subVDIDepthBuffer: ByteBuffer? = null
            var subVDIColorBuffer: ByteBuffer?
            var bufferToSend: ByteBuffer? = null

            var compositedVDIDepthBuffer: ByteBuffer?
            var compositedVDIColorBuffer: ByteBuffer?

            start = System.nanoTime()
            while((vdisGenerated.get() <= generatedSoFar)) {
                Thread.sleep(5)
            }
            end = System.nanoTime() - start

//            logger.info("Waiting for VDI generation took: ${end/1e9}")


//            logger.warn("C1: vdis generated so far: $generatedSoFar and the new value of vdisgenerated: ${vdisGenerated.get()}")

            generatedSoFar = vdisGenerated.get()

            subVDIColorBuffer = colorTexture.contents
            if(separateDepth) {
                subVDIDepthBuffer = depthTexture!!.contents
            }
            compositedVDIColorBuffer = compositedColor.contents
            compositedVDIDepthBuffer = compositedDepth.contents

//
//            compositor.ViewOriginal = vdiData.metadata.view
//            compositor.invViewOriginal = Matrix4f(vdiData.metadata.view).invert()
//            compositor.ProjectionOriginal = Matrix4f(vdiData.metadata.projection).applyVulkanCoordinateSystem()
//            compositor.invProjectionOriginal = Matrix4f(vdiData.metadata.projection).applyVulkanCoordinateSystem().invert()

            if(!benchmarking) {
                logger.info("Dumping sub VDI files")
                SystemHelpers.dumpToFile(subVDIColorBuffer!!, basePath + "${dataset}SubVDI_${windowWidth}_${windowHeight}_${maxSupersegments}_0_${cnt_sub}_ndc_col")
                SystemHelpers.dumpToFile(subVDIDepthBuffer!!, basePath + "${dataset}SubVDI_${windowWidth}_${windowHeight}_${maxSupersegments}_0_${cnt_sub}_ndc_depth")
                logger.info("File dumped")
            }

            if(subVDIColorBuffer == null || subVDIDepthBuffer == null) {
                logger.info("CALLING DISTRIBUTE EVEN THOUGH THE BUFFERS ARE NULL!!")
            }

//            logger.info("For distributed the color buffer isdirect: ${subVDIColorBuffer!!.isDirect()} and depthbuffer: ${subVDIDepthBuffer!!.isDirect()}")

            start = System.nanoTime()
            distributeVDIs(subVDIColorBuffer!!, subVDIDepthBuffer!!, windowHeight * windowWidth * maxSupersegments * 4 / commSize, commSize, allToAllColorPointer,
            allToAllDepthPointer, mpiPointer)
            end = System.nanoTime() - start

//            logger.info("Distributing VDIs took: ${end/1e9}")


//            logger.info("Back in the management function")

            if(!benchmarking) {
//                Thread.sleep(1000)
//
//                subVDIColorBuffer.clear()
//                subVDIDepthBuffer.clear()
            }

            start = System.nanoTime()
            while(vdisComposited.get() <= compositedSoFar) {
                Thread.sleep(5)
            }
            end = System.nanoTime() - start

//            logger.info("Waiting for composited generation took: ${end/1e9}")

            compositedSoFar = vdisComposited.get()

            //fetch the composited VDI

            if(compositedVDIColorBuffer == null || compositedVDIDepthBuffer == null) {
                logger.info("CALLING GATHER EVEN THOUGH THE BUFFER(S) ARE NULL!!")
            }

            if(!benchmarking) {
                logger.info("Dumping sub VDI files")
                SystemHelpers.dumpToFile(compositedVDIColorBuffer!!, basePath + "${dataset}CompositedVDI${cnt_sub}_ndc_col")
                SystemHelpers.dumpToFile(compositedVDIDepthBuffer!!, basePath + "${dataset}CompositedVDI${cnt_sub}_ndc_depth")
                logger.info("File dumped")

                logger.info("Cam position: ${cam.spatial().position}")
                logger.info("Cam rotation: ${cam.spatial().rotation}")

                cnt_sub++
            }

//            logger.info("For gather the color buffer isdirect: ${compositedVDIColorBuffer!!.isDirect()} and depthbuffer: ${compositedVDIDepthBuffer!!.isDirect()}")

            start = System.nanoTime()
            gatherCompositedVDIs(compositedVDIColorBuffer!!, compositedVDIDepthBuffer!!, windowHeight * windowWidth * maxOutputSupersegments * 4 / commSize, 0,
                rank, commSize, gatherColorPointer, gatherDepthPointer, mpiPointer) //3 * commSize because the supersegments here contain only 1 element
            end = System.nanoTime() - start

//            logger.info("Gather took: ${end/1e9}")


            if(saveFinal && (rank == 0)) {
                val file = FileOutputStream(File(basePath + "${dataset}vdi_${windowWidth}_${windowHeight}_${maxSupersegments}_0_dump$vdisGathered"))
                VDIDataIO.write(vdiData, file)
                logger.info("written the dump $vdisGathered")
                file.close()
            }

            vdisGathered++

            if(!benchmarking) {
//                Thread.sleep(1000)
//
//                compositedVDIColorBuffer.clear()
//                compositedVDIDepthBuffer.clear()
//
                logger.info("Back in the management function after gathering and streaming")
//                Thread.sleep(1000)
            }

            end_complete = System.nanoTime() - start_complete

//            logger.info("Whole iteration took: ${end_complete/1e9}")
        }
    }

    override fun inputSetup() {
        setupCameraModeSwitching()

//        inputHandler?.addBehaviour("rotate_camera", ClickBehaviour { _, _ ->
//            addVolume(0, intArrayOf(200, 200, 200), floatArrayOf(0f, 0f, -3.5f))
//        })
//        inputHandler?.addKeyBinding("rotate_camera", "R")
    }

    @Suppress("unused")
    fun uploadForCompositingDense(vdiSetColour: ByteBuffer, vdiSetDepth: ByteBuffer, prefixSet: ByteBuffer, colorLimits: IntArray, depthLimits: IntArray) {

        val prefixIntBuffer = prefixSet.asIntBuffer()
        var prefixAdded = 0
        for(i in 1 until commSize) {
            val numSupsegs = colorLimits[i-1] / (4*4)
            prefixAdded += numSupsegs
            for (j in (i * ((windowWidth * windowHeight)/commSize)) until ((i+1) * (windowWidth*windowHeight)/commSize)) {
                logger.info("prefix was: ${prefixIntBuffer.get(j)}")
                prefixIntBuffer.put(j, prefixIntBuffer.get(j) + prefixAdded)
                logger.info("updated to: ${prefixIntBuffer.get(j)}")
            }
        }

        logger.info("Dumping to file in the composite function")
        SystemHelpers.dumpToFile(vdiSetColour, basePath + "${dataset}SetOfVDI_${windowWidth}_${windowHeight}_${maxSupersegments}_0_${cnt_distr}_ndc_col_rle")
        SystemHelpers.dumpToFile(vdiSetDepth, basePath + "${dataset}SetOfVDI_${windowWidth}_${windowHeight}_${maxSupersegments}_0_${cnt_distr}_ndc_depth_rle")
        SystemHelpers.dumpToFile(prefixSet, basePath + "${dataset}SetOfVDI_${windowWidth}_${windowHeight}_${maxSupersegments}_0_${cnt_distr}_ndc_prefix")
        logger.info("File dumped")
        cnt_distr++

        vdisDistributed.incrementAndGet()

    }


    @Suppress("unused")
    fun uploadForCompositing(vdiSetColour: ByteBuffer, vdiSetDepth: ByteBuffer) {
        //Receive the VDIs and composite them

//        logger.info("In the composite function")

//        if(saveFiles) {
        val model = volumes[0]?.spatial()?.world

//        val vdiData = VDIData(
//            VDIMetadata(
//                projection = cam.spatial().projection,
//                view = cam.spatial().getTransformation(),
//                volumeDimensions = volumeDims,
//                model = model!!,
//                nw = volumes[0]?.volumeManager?.shaderProperties?.get("nw") as Float,
//                windowDimensions = Vector2i(cam.width, cam.height)
//            )
//        )
//
//        val duration = measureNanoTime {
//            val file = FileOutputStream(File(basePath + "${dataset}vdidump$cnt_distr"))
////                    val comp = GZIPOutputStream(file, 65536)
//            VDIDataIO.write(vdiData, file)
//            logger.info("written the dump")
//            file.close()
//        }

//        if(!benchmarking) {
            logger.info("Dumping to file in the composite function")
            SystemHelpers.dumpToFile(vdiSetColour, basePath + "${dataset}SetOfVDI_${windowWidth}_${windowHeight}_${maxSupersegments}_0_${cnt_distr}_ndc_col")
            SystemHelpers.dumpToFile(vdiSetDepth, basePath + "${dataset}SetOfVDI_${windowWidth}_${windowHeight}_${maxSupersegments}_0_${cnt_distr}_ndc_depth")
            logger.info("File dumped")
            cnt_distr++
//        }
//        }

        if(generateVDIs) {
            compositor.material().textures["VDIsColor"] = Texture(Vector3i(maxSupersegments, windowHeight, windowWidth), 4, contents = vdiSetColour, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture),
                    type = FloatType(), mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)

            if(separateDepth) {
                compositor.material().textures["VDIsDepth"] = Texture(Vector3i(2 * maxSupersegments, windowHeight, windowWidth), 1, contents = vdiSetDepth, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture),
                    type = FloatType(), mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)
            }
        } else {
            compositor.material().textures["VDIsColor"] = Texture(Vector3i(windowHeight, windowWidth, 1), 4, contents = vdiSetColour, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
            compositor.material().textures["VDIsDepth"] = Texture(Vector3i(windowHeight, windowWidth, 1), 4, contents = vdiSetDepth, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
        }
//        logger.info("Updated the textures to be composited")

        vdisDistributed.incrementAndGet()

//        compute.visible = true
    }


    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            DistributedVolumes().main()
        }
    }
}