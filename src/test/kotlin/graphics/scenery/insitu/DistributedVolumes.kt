package graphics.scenery.insitu

import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.backends.Shaders
import graphics.scenery.backends.vulkan.VulkanRenderer
import graphics.scenery.compute.ComputeMetadata
import graphics.scenery.compute.InvocationType
import graphics.scenery.controls.TrackedStereoGlasses
import graphics.scenery.textures.Texture
import graphics.scenery.utils.Image
import graphics.scenery.utils.SystemHelpers
import graphics.scenery.volumes.*
import graphics.scenery.volumes.vdi.VDIData
import graphics.scenery.volumes.vdi.VDIDataIO
import graphics.scenery.volumes.vdi.VDIMetadata
import net.imglib2.type.numeric.integer.UnsignedByteType
import net.imglib2.type.numeric.integer.UnsignedIntType
import net.imglib2.type.numeric.integer.UnsignedShortType
import net.imglib2.type.numeric.real.FloatType
import org.joml.*
import org.lwjgl.system.MemoryUtil
import org.scijava.ui.behaviour.ClickBehaviour
import tpietzsch.shadergen.generate.SegmentTemplate
import tpietzsch.shadergen.generate.SegmentType
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.streams.toList
import kotlin.system.measureNanoTime

class CustomNode : RichNode() {
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
    val compositor = CustomNode()
    val generateVDIs = true
    val separateDepth = true
    val colors32bit = true
    val saveFiles = false
    val cam: Camera = DetachedHeadCamera(hmd)

    val maxSupersegments = 20
    var maxOutputSupersegments = 20
    var numLayers = 0

    var commSize = 1
    var rank = 0
    var pixelToWorld = 0.001f
    val volumeDims = Vector3f(832f, 832f, 494f)
    var dataset = "DistributedStagbeetle"
    var cnt_distr = 0
    var cnt_sub = 0
    var isCluster = false
    var basePath = ""
    var rendererConfigured = false

    var mpiPointer = 0L
    var allToAllColorPointer = 0L
    var allToAllDepthPointer = 0L
    var gatherColorPointer = 0L
    var gatherDepthPointer = 0L

    var volumesCreated = false
    var volumeManagerInitialized = false

    var vdisGenerated = AtomicInteger(0)
    var vdisDistributed = AtomicInteger(0)
    var vdisComposited = AtomicInteger(0)

    var runGeneration = true
    var runCompositing = false

    val colorMap = Colormap.get("hot")

    var dims = Vector3i(0)

    private external fun distributeVDIs(subVDIColor: ByteBuffer, subVDIDepth: ByteBuffer, sizePerProcess: Int, commSize: Int,
        colPointer: Long, depthPointer: Long, mpiPointer: Long)
    private external fun gatherCompositedVDIs(compositedVDIColor: ByteBuffer, compositedVDIDepth: ByteBuffer, compositedVDILen: Int, root: Int, myRank: Int, commSize: Int,
        colPointer: Long, depthPointer: Long, mpiPointer: Long)

    fun addVolume(volumeID: Int, dimensions: IntArray, pos: FloatArray, is16bit: Boolean = true) {
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
//        volume.pixelToWorldRatio = pixelToWorld

//        with(volume.transferFunction) {
//            this.addControlPoint(0.0f, 0.0f)
//            this.addControlPoint(0.2f, 0.1f)
//            this.addControlPoint(0.4f, 0.4f)
//            this.addControlPoint(0.8f, 0.6f)
//            this.addControlPoint(1.0f, 0.75f)
//        }

        val tf = TransferFunction()
        with(tf) {
            addControlPoint(0.0f, 0.0f)
            addControlPoint(0.005f, 0.0f)
            addControlPoint(0.01f, 0.3f)
        }


        volume.transferFunction = tf

        scene.addChild(volume)

        volumes[volumeID] = volume


        volumesCreated = true
    }

    @Suppress("unused")
    fun updateVolume(volumeID: Int, buffer: ByteBuffer) {
        while(volumes[volumeID] == null) {
            Thread.sleep(50)
        }
        logger.info("Updating the volume!")
        volumes[volumeID]?.addTimepoint("t", buffer)
        volumes[volumeID]?.goToLastTimepoint()
    }

    fun bufferFromPath(file: Path): ByteBuffer {

        val infoFile: Path
        val volumeFiles: List<Path>

        if(Files.isDirectory(file)) {
            volumeFiles = Files.list(file).filter { it.toString().endsWith(".raw") && Files.isRegularFile(it) && Files.isReadable(it) }.toList()
            infoFile = file.resolve("stacks.info")
        } else {
            volumeFiles = listOf(file)
            infoFile = file.resolveSibling("stacks.info")
        }

        val lines = Files.lines(infoFile).toList()

        logger.debug("reading stacks.info (${lines.joinToString()}) (${lines.size} lines)")
        val dimensions = Vector3i(lines.get(0).split(",").map { it.toInt() }.toIntArray())
        dims = dimensions
        logger.debug("setting dim to ${dimensions.x}/${dimensions.y}/${dimensions.z}")
        logger.debug("Got ${volumeFiles.size} volumes")


        val volumes = CopyOnWriteArrayList<BufferedVolume.Timepoint>()
        val v = volumeFiles.first()
        val id = v.fileName.toString()
        val buffer: ByteBuffer by lazy {

            logger.debug("Loading $id from disk")
            val buffer = ByteArray(1024 * 1024)
            val stream = FileInputStream(v.toFile())
            val imageData: ByteBuffer = MemoryUtil.memAlloc((2 * dimensions.x * dimensions.y * dimensions.z))

            logger.debug("${v.fileName}: Allocated ${imageData.capacity()} bytes for UINT16 image of $dimensions")

            val start = System.nanoTime()
            var bytesRead = stream.read(buffer, 0, buffer.size)
            while (bytesRead > -1) {
                imageData.put(buffer, 0, bytesRead)
                bytesRead = stream.read(buffer, 0, buffer.size)
            }
            val duration = (System.nanoTime() - start) / 10e5
            logger.debug("Reading took $duration ms")

            imageData.flip()
            imageData
        }
        return buffer
    }

    fun setupVolumeManager() {
        val raycastShader: String
        val accumulateShader: String
        val compositeShader: String

        if(generateVDIs) {
            raycastShader = "VDIGenerator.comp"
            accumulateShader = "AccumulateVDI.comp"
            compositeShader = "VDICompositor.comp"
            numLayers = if(separateDepth) {
                1
            } else {
                3         // VDI supersegments require both front and back depth values, along with color
            }

            volumeManager = VolumeManager(
                hub, useCompute = true, customSegments = hashMapOf(
                    SegmentType.FragmentShader to SegmentTemplate(
                        this.javaClass,
                        raycastShader,
                        "intersectBoundingBox", "vis", "localNear", "localFar", "SampleVolume", "Convert", "Accumulate",
                    ),
                    SegmentType.Accumulator to SegmentTemplate(
//                                this.javaClass,
                        accumulateShader,
                        "vis", "localNear", "localFar", "sampleVolume", "convert",
                    ),
                ),
            )

            val colorBuffer = if(colors32bit) {
                MemoryUtil.memCalloc(windowHeight*windowWidth*4*maxSupersegments*numLayers * 4)
            } else {
                MemoryUtil.memCalloc(windowHeight*windowWidth*4*maxSupersegments*numLayers)
            }
            val depthBuffer = if(separateDepth) {
                MemoryUtil.memCalloc(windowHeight*windowWidth*4*maxSupersegments*2)
            } else {
                MemoryUtil.memCalloc(0)
            }

            val numGridCells = Vector3f(windowWidth.toFloat() / 8f, windowHeight.toFloat() / 8f, maxSupersegments.toFloat())
            val lowestLevel = MemoryUtil.memCalloc(numGridCells.x.toInt() * numGridCells.y.toInt() * numGridCells.z.toInt() * 4)

            val colorTexture: Texture
            val depthTexture: Texture
            val gridCells: Texture

            colorTexture = if(colors32bit) {
                Texture.fromImage(
                    Image(colorBuffer, numLayers * maxSupersegments, windowHeight, windowWidth), usage = hashSetOf(
                        Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture), type = FloatType(), channels = 4, mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)
            } else {
                Texture.fromImage(
                    Image(colorBuffer, numLayers * maxSupersegments, windowHeight, windowWidth), usage = hashSetOf(
                        Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
            }

            volumeManager.customTextures.add("OutputSubVDIColor")
            volumeManager.material().textures["OutputSubVDIColor"] = colorTexture

            if(separateDepth) {
                depthTexture = Texture.fromImage(
                    Image(depthBuffer, 2*maxSupersegments, windowHeight, windowWidth),  usage = hashSetOf(
                        Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture), type = FloatType(), channels = 1, mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)
                volumeManager.customTextures.add("OutputSubVDIDepth")
                volumeManager.material().textures["OutputSubVDIDepth"] = depthTexture
            }

            gridCells = Texture.fromImage(
                Image(lowestLevel, numGridCells.x.toInt(), numGridCells.y.toInt(), numGridCells.z.toInt()), channels = 1, type = UnsignedIntType(),
                usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
            volumeManager.customTextures.add("OctreeCells")
            volumeManager.material().textures["OctreeCells"] = gridCells

            hub.add(volumeManager)

            val compute = RichNode()
            compute.setMaterial(ShaderMaterial(Shaders.ShadersFromFiles(arrayOf("GridCellsToZero.comp"), this@DistributedVolumes::class.java)))

            compute.metadata["ComputeMetadata"] = ComputeMetadata(
                workSizes = Vector3i(numGridCells.x.toInt(), numGridCells.y.toInt(), 1),
                invocationType = InvocationType.Permanent
            )

            compute.material().textures["GridCells"] = gridCells

            scene.addChild(compute)

        } else {
            raycastShader = "VDIGenerator.comp"
            accumulateShader = "AccumulateVDI.comp"
            compositeShader = "AlphaCompositor.comp"
            val volumeManager = VolumeManager(hub,
                useCompute = true,
                customSegments = hashMapOf(
                    SegmentType.FragmentShader to SegmentTemplate(
                        this.javaClass,
                        "ComputeRaycast.comp",
                        "intersectBoundingBox", "vis", "localNear", "localFar", "SampleVolume", "Convert", "Accumulate"),
                ))
            volumeManager.customTextures.add("OutputRender") //TODO: attach depth texture required for compositing

            val outputBuffer = MemoryUtil.memCalloc(windowWidth*windowHeight*4)
            val outputTexture = Texture.fromImage(
                Image(outputBuffer, windowWidth, windowHeight), usage = hashSetOf(
                    Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
            volumeManager.material().textures["OutputRender"] = outputTexture

            hub.add(volumeManager)

            val plane = FullscreenObject()
            scene.addChild(plane)
            plane.material().textures["diffuse"] = volumeManager.material().textures["OutputRender"]!!
        }

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
            workSizes = Vector3i(windowHeight, windowWidth/commSize, 1)
        )
        compositor.visible = true
        scene.addChild(compositor)
    }

    override fun init() {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        setupVolumeManager()
        volumeManagerInitialized = true

        with(cam) {
            spatial {
                position = Vector3f(3.174E+0f, -1.326E+0f, -2.554E+0f)
                rotation = Quaternionf(-1.276E-2,  9.791E-1,  6.503E-2, -1.921E-1)
            }
            perspectiveCamera(50.0f, windowWidth, windowHeight)
            cam.farPlaneDistance = 20.0f

            scene.addChild(this)
        }

//        val shell = Box(Vector3f(10.0f, 10.0f, 10.0f), insideNormals = true)
//        shell.material {
//            cullingMode = Material.CullingMode.None
//            diffuse = Vector3f(0.2f, 0.2f, 0.2f)
//            specular = Vector3f(0.0f)
//            ambient = Vector3f(0.0f)
//        }
//        scene.addChild(shell)

//        addVolume(0, intArrayOf(200, 200, 200), floatArrayOf(0f, 0f, -3.5f))

        val lights = (0 until 3).map {
            PointLight(radius = 15.0f)
        }

        lights.mapIndexed { i, light ->
            light.spatial().position = Vector3f(2.0f * i - 4.0f,  i - 1.0f, 0.0f)
            light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
            light.intensity = 0.5f
            scene.addChild(light)
        }

//        while(!volumesCreated) {
//            Thread.sleep(50)
//        }

        logger.info("Exiting init function!")
        thread {
            if(generateVDIs) {
                manageVDIGeneration()
            }
        }

//        (renderer as VulkanRenderer).postRenderLambdas.add {
//            if(runGeneration) {
//                vdisGenerated.incrementAndGet()
//                runGeneration = false
//            }
//            if(runCompositing) {
//                vdisComposited.incrementAndGet()
//                runCompositing = false
//                runGeneration = true
//            }
//
//            if(vdisDistributed.get() > vdisComposited.get()) {
//                runCompositing = true
//            }
//        }
    }

    private fun storeSubVDIs() {
        var subVDIDepthBuffer: ByteBuffer? = null
        var subVDIColorBuffer: ByteBuffer?
        var gridCellsBuff: ByteBuffer?

        while(renderer?.firstImageReady == false) {
//        while(renderer?.firstImageReady == false || volumeManager.shaderProperties.isEmpty()) {
            Thread.sleep(50)
        }

        while(!rendererConfigured) {
            Thread.sleep(50)
        }

        val subVDIColor = volumeManager.material().textures["OutputSubVDIColor"]!!
        val subvdi = AtomicInteger(0)

        (renderer as? VulkanRenderer)?.persistentTextureRequests?.add (subVDIColor to subvdi)

        val subvdiCnt = AtomicInteger(0)
        var subVDIDepth: Texture? = null

        if(separateDepth) {
            subVDIDepth = volumeManager.material().textures["OutputSubVDIDepth"]!!
            (renderer as? VulkanRenderer)?.persistentTextureRequests?.add (subVDIDepth to subvdiCnt)
        }

        val gridCells = volumeManager.material().textures["OctreeCells"]!!
        val gridTexturesCnt = AtomicInteger(0)

        (renderer as? VulkanRenderer)?.persistentTextureRequests?.add (gridCells to gridTexturesCnt)

        var prevAtomic = subvdi.get()

        var cnt = 0

        val tGeneration = Timer(0, 0)

        dataset += "_${commSize}_${rank}"

        while (true) {
            tGeneration.start = System.nanoTime()
            while(subvdi.get() == prevAtomic) {
                Thread.sleep(5)
            }
            prevAtomic = subvdi.get()
            subVDIColorBuffer = subVDIColor.contents
            if(separateDepth) {
                subVDIDepthBuffer = subVDIDepth!!.contents
            }
            gridCellsBuff = gridCells.contents

            tGeneration.end = System.nanoTime()

            val timeTaken = tGeneration.end - tGeneration.start

            logger.info("Time taken for generation (only correct if VDIs were not being written to disk): ${timeTaken}")

            val camera = cam

            val model = volumes[0]?.spatial()?.world

            logger.info("The model matrix added to the vdi is: $model.")

            if(cnt < 20) {

                logger.info(volumeManager.shaderProperties.keys.joinToString())

                val total_duration = measureNanoTime {
                    val vdiData = VDIData(
                        VDIMetadata(
                            projection = camera.spatial().projection,
                            view = camera.spatial().getTransformation(),
                            volumeDimensions = volumeDims,
                            model = model!!,
                            nw = volumes[0]?.volumeManager?.shaderProperties?.get("nw") as Float,
                            windowDimensions = Vector2i(camera.width, camera.height)
                        )
                    )

                    val duration = measureNanoTime {
                        val file = FileOutputStream(File(basePath + "${dataset}vdidump$cnt"))
//                    val comp = GZIPOutputStream(file, 65536)
                        VDIDataIO.write(vdiData, file)
                        logger.info("written the dump")
                        file.close()
                    }
                    logger.info("time taken (uncompressed): ${duration}")
                }

                logger.info("total serialization duration: ${total_duration}")

                var fileName = ""
                fileName = "${dataset}VDI${cnt}_ndc"
                if(separateDepth) {
                    SystemHelpers.dumpToFile(subVDIColorBuffer!!, basePath + "${fileName}_col")
                    SystemHelpers.dumpToFile(subVDIDepthBuffer!!, basePath + "${fileName}_depth")
                    SystemHelpers.dumpToFile(gridCellsBuff!!, basePath + "${fileName}_octree")
                } else {
                    SystemHelpers.dumpToFile(subVDIColorBuffer!!, fileName)
                    SystemHelpers.dumpToFile(gridCellsBuff!!, basePath + "${fileName}_octree")
                }
                logger.info("Wrote VDI $cnt")
                logger.info("commsize: $commSize and rank: $rank")
            }
            cnt++
        }
    }

    @Suppress("unused")
    fun stopRendering() {
        renderer?.shouldClose = true
    }

    private fun manageVDIGeneration() {

        while(renderer?.firstImageReady == false) {
            Thread.sleep(50)
        }

        while(!rendererConfigured) {
            Thread.sleep(50)
        }

        basePath = if(isCluster) {
            "/scratch/ws/1/argupta-distributed_vdis/vdi_dumps/"
        } else {
            "/home/aryaman/TestingData/"
        }

        val model = volumes[0]?.spatial()?.world

        val vdiData = VDIData(
            VDIMetadata(
                projection = cam.spatial().projection,
                view = cam.spatial().getTransformation(),
                volumeDimensions = volumeDims,
                model = model!!,
                nw = volumes[0]?.volumeManager?.shaderProperties?.get("nw") as Float,
                windowDimensions = Vector2i(cam.width, cam.height)
            )
        )

        compositor.nw = vdiData.metadata.nw
        compositor.ViewOriginal = vdiData.metadata.view
        compositor.invViewOriginal = Matrix4f(vdiData.metadata.view).invert()
        compositor.ProjectionOriginal = Matrix4f(vdiData.metadata.projection).applyVulkanCoordinateSystem()
        compositor.invProjectionOriginal = Matrix4f(vdiData.metadata.projection).applyVulkanCoordinateSystem().invert()

        val colorTexture = volumeManager.material().textures["OutputSubVDIColor"]!!
        val depthTexture = volumeManager.material().textures["OutputSubVDIDepth"]!!

        val compositedColor =
            if(generateVDIs) {
                compositor.material().textures["CompositedVDIColor"]!!
            } else {
                compositor.material().textures["AlphaComposited"]!!
            }
        val compositedDepth = compositor.material().textures["CompositedVDIDepth"]!!

        val c1 = AtomicInteger(0)
        val c2 = AtomicInteger(0)
        val c3 = AtomicInteger(0)
        val c4 = AtomicInteger(0)

        (renderer as? VulkanRenderer)?.persistentTextureRequests?.add (colorTexture to c1)

        if(separateDepth) {
            (renderer as? VulkanRenderer)?.persistentTextureRequests?.add (depthTexture to c2)
        }

        (renderer as? VulkanRenderer)?.persistentTextureRequests?.add (compositedColor to c3)

        (renderer as? VulkanRenderer)?.persistentTextureRequests?.add (compositedDepth to c4)

        var prevC1 = c1.get()
        var prevC2 = c2.get()

        var prevC3 = c3.get()
        var prevC4 = c4.get()

        dataset += "_${commSize}_${rank}"

        while(true) {

            var subVDIDepthBuffer: ByteBuffer? = null
            var subVDIColorBuffer: ByteBuffer?
            var bufferToSend: ByteBuffer? = null

            var compositedVDIDepthBuffer: ByteBuffer?
            var compositedVDIColorBuffer: ByteBuffer?

            while((c1.get() == prevC1) || (c2.get() == prevC2)) {
                Thread.sleep(5)
            }

            logger.warn("C1: Previous value was: $prevC1 and the new value is ${c1.get()}")
            logger.warn("C2: Previous value was: $prevC2 and the new value is ${c2.get()}")

            prevC1 = c1.get()
            prevC2 = c2.get()

            subVDIColorBuffer = colorTexture.contents
            if(separateDepth) {
                subVDIDepthBuffer = depthTexture!!.contents
            }
            compositedVDIColorBuffer = compositedColor.contents
            compositedVDIDepthBuffer = compositedDepth.contents

//            vdiData.metadata.projection = cam.spatial().projection //TODO: uncomment and debug to allow changing of viewpoint
//            vdiData.metadata.view = cam.spatial().getTransformation()
//
//            compositor.ViewOriginal = vdiData.metadata.view
//            compositor.invViewOriginal = Matrix4f(vdiData.metadata.view).invert()
//            compositor.ProjectionOriginal = Matrix4f(vdiData.metadata.projection).applyVulkanCoordinateSystem()
//            compositor.invProjectionOriginal = Matrix4f(vdiData.metadata.projection).applyVulkanCoordinateSystem().invert()

            val duration = measureNanoTime {
                val file = FileOutputStream(File(basePath + "${dataset}vdidump$cnt_sub"))
//                    val comp = GZIPOutputStream(file, 65536)
                VDIDataIO.write(vdiData, file)
                logger.info("written the dump")
                file.close()
            }

            logger.info("Dumping to file in the composite function")
            SystemHelpers.dumpToFile(subVDIColorBuffer!!, basePath + "${dataset}SubVDI${cnt_sub}_ndc_col")
            SystemHelpers.dumpToFile(subVDIDepthBuffer!!, basePath + "${dataset}SubVDI${cnt_sub}_ndc_depth")
            logger.info("File dumped")
            cnt_sub++

            if(subVDIColorBuffer == null || subVDIDepthBuffer == null) {
                logger.info("CALLING DISTRIBUTE EVEN THOUGH THE BUFFERS ARE NULL!!")
            }

            logger.info("For distributed the color buffer isdirect: ${subVDIColorBuffer.isDirect()} and depthbuffer: ${subVDIDepthBuffer.isDirect()}")

            distributeVDIs(subVDIColorBuffer!!, subVDIDepthBuffer!!, windowHeight * windowWidth * maxSupersegments * 4 / commSize, commSize, allToAllColorPointer,
            allToAllDepthPointer, mpiPointer)

            logger.info("Back in the management function")

            Thread.sleep(5000)

            subVDIColorBuffer.clear()
            subVDIDepthBuffer.clear()

            while((c3.get() == prevC3) || (c4.get() == prevC4)) {
                Thread.sleep(5)
            }

            logger.warn("C3: Previous value was: $prevC3 and the new value is ${c3.get()}")
            logger.warn("C4: Previous value was: $prevC4 and the new value is ${c4.get()}")

            prevC3 = c3.get()
            prevC4 = c4.get()

            //fetch the composited VDI

//            if(saveFiles) {
//                logger.info("Dumping to file")
//                SystemHelpers.dumpToFile(compositedVDIColorBuffer!!, "$rank:textureCompCol-${SystemHelpers.formatDateTime(delimiter = "_")}.raw")
////                SystemHelpers.dumpToFile(compositedVDIDepthBuffer!!, "$rank:textureCompDepth-${SystemHelpers.formatDateTime(delimiter = "_")}.raw")
//                logger.info("File dumped")
//            }

            logger.info("For gather the color buffer isdirect: ${compositedVDIColorBuffer!!.isDirect()} and depthbuffer: ${compositedVDIDepthBuffer!!.isDirect()}")

            gatherCompositedVDIs(compositedVDIColorBuffer!!, compositedVDIDepthBuffer!!, windowHeight * windowWidth * maxOutputSupersegments * 4 * numLayers/ commSize, 0,
                rank, commSize, gatherColorPointer, gatherDepthPointer, mpiPointer) //3 * commSize because the supersegments here contain only 1 element

            Thread.sleep(5000)

            compositedVDIColorBuffer.clear()
            compositedVDIDepthBuffer.clear()

            logger.info("Back in the management function after gathering and streaming")
            Thread.sleep(2000)

//            compositedVDIColorBuffer = null

//            saveFiles = false
        }
    }

    override fun inputSetup() {
        setupCameraModeSwitching()

        inputHandler?.addBehaviour("rotate_camera", ClickBehaviour { _, _ ->
            addVolume(0, intArrayOf(200, 200, 200), floatArrayOf(0f, 0f, -3.5f))
        })
        inputHandler?.addKeyBinding("rotate_camera", "R")
    }

    @Suppress("unused")
    fun compositeVDIs(vdiSetColour: ByteBuffer, vdiSetDepth: ByteBuffer) {
        //Receive the VDIs and composite them

        logger.info("In the composite function")

//        if(saveFiles) {
        val model = volumes[0]?.spatial()?.world

        val vdiData = VDIData(
            VDIMetadata(
                projection = cam.spatial().projection,
                view = cam.spatial().getTransformation(),
                volumeDimensions = volumeDims,
                model = model!!,
                nw = volumes[0]?.volumeManager?.shaderProperties?.get("nw") as Float,
                windowDimensions = Vector2i(cam.width, cam.height)
            )
        )

        val duration = measureNanoTime {
            val file = FileOutputStream(File(basePath + "${dataset}vdidump$cnt_distr"))
//                    val comp = GZIPOutputStream(file, 65536)
            VDIDataIO.write(vdiData, file)
            logger.info("written the dump")
            file.close()
        }

        logger.info("Dumping to file in the composite function")
        SystemHelpers.dumpToFile(vdiSetColour, basePath + "${dataset}SetOfVDI${cnt_distr}_ndc_col")
        SystemHelpers.dumpToFile(vdiSetDepth, basePath + "${dataset}SetOfVDI${cnt_distr}_ndc_depth")
        logger.info("File dumped")
        cnt_distr++
//        }

        if(generateVDIs) {
            compositor.material().textures["VDIsColor"] = Texture(Vector3i(maxSupersegments * numLayers, windowHeight, windowWidth), 4, contents = vdiSetColour, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture),
                    type = FloatType(), mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)

            if(separateDepth) {
                compositor.material().textures["VDIsDepth"] = Texture(Vector3i(2 * maxSupersegments, windowHeight, windowWidth), 1, contents = vdiSetDepth, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture),
                    type = FloatType(), mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)
            }
        } else {
            compositor.material().textures["VDIsColor"] = Texture(Vector3i(windowHeight, windowWidth, 1), 4, contents = vdiSetColour, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
            compositor.material().textures["VDIsDepth"] = Texture(Vector3i(windowHeight, windowWidth, 1), 4, contents = vdiSetDepth, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
        }
        logger.info("Updated the textures to be composited")

//        compute.visible = true
    }


    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            DistributedVolumes().main()
        }
    }
}