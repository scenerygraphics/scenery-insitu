package graphics.scenery.insitu

import bdv.tools.transformation.TransformedSource
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.controls.TrackedStereoGlasses
import graphics.scenery.attribute.material.Material
import graphics.scenery.backends.Shaders
import graphics.scenery.backends.vulkan.VulkanRenderer
import graphics.scenery.compute.ComputeMetadata
import graphics.scenery.compute.InvocationType
import graphics.scenery.textures.Texture
import graphics.scenery.utils.Image
import graphics.scenery.utils.Statistics
import graphics.scenery.utils.SystemHelpers
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import graphics.scenery.volumes.*
import graphics.scenery.volumes.vdi.VDIData
import graphics.scenery.volumes.vdi.VDIDataIO
import graphics.scenery.volumes.vdi.VDIMetadata
import kotlinx.coroutines.delay
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
import java.lang.Math
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.log
import kotlin.math.pow
import kotlin.streams.toList
import kotlin.system.measureNanoTime

/**
 * Class to test volume rendering performance on data loaded from file
 *
 * @author Aryaman Gupta <argupta@mpi-cbg.de>
 */

data class Timer(var start: Long, var end: Long)

class VolumeFromFileExample: SceneryBase("Volume Rendering", 1832, 1016) {
    var hmd: TrackedStereoGlasses? = null

    lateinit var volumeManager: VolumeManager
    val generateVDIs = false
    val separateDepth = true
    val colors32bit = true
    val world_abs = false
    val dataset = "Stagbeetle_divided"
    val num_parts =  2
    val volumeDims = Vector3f(832f, 832f, 494f)
    val is16bit = true
    val volumeList = ArrayList<BufferedVolume>()
    val cam: Camera = DetachedHeadCamera(hmd)
    var camTarget = Vector3f(0f)
    val numOctreeLayers = 8
    val maxSupersegments = 20
    var benchmarking = false
    val viewNumber = 1

    val closeAfter = 250000L

    /**
     * Reads raw volumetric data from a [file].
     *
     * Returns the new volume.
     *
     * Based on Volume.fromPathRaw
     */
    fun fromPathRaw(file: Path, is16bit: Boolean = true): BufferedVolume {

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
        logger.debug("setting dim to ${dimensions.x}/${dimensions.y}/${dimensions.z}")
        logger.debug("Got ${volumeFiles.size} volumes")

        val volumes = CopyOnWriteArrayList<BufferedVolume.Timepoint>()
        volumeFiles.forEach { v ->
            val id = v.fileName.toString()
            val buffer: ByteBuffer by lazy {

                logger.debug("Loading $id from disk")
                val buffer = ByteArray(1024 * 1024)
                val stream = FileInputStream(v.toFile())
                val numBytes = if(is16bit) {
                    2
                } else {
                    1
                }
                val imageData: ByteBuffer = MemoryUtil.memAlloc((numBytes * dimensions.x * dimensions.y * dimensions.z))

                logger.debug("${v.fileName}: Allocated ${imageData.capacity()} bytes for image of $dimensions containing $numBytes per voxel")

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

            volumes.add(BufferedVolume.Timepoint(id, buffer))
        }

        return if(is16bit) {
            Volume.fromBuffer(volumes, dimensions.x, dimensions.y, dimensions.z, UnsignedShortType(), hub)
        } else {
            Volume.fromBuffer(volumes, dimensions.x, dimensions.y, dimensions.z, UnsignedByteType(), hub)
        }
    }

    override fun init() {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val raycastShader: String
        val accumulateShader: String
        val compositeShader: String

        if(generateVDIs) {
            benchmarking = false
            raycastShader = "VDIGenerator.comp"
            accumulateShader = "AccumulateVDI.comp"
            compositeShader = "VDICompositor.comp"
            val maxOutputSupersegments = 40
            val numLayers = if(separateDepth) {
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

            val outputSubColorBuffer = if(colors32bit) {
                MemoryUtil.memCalloc(windowHeight*windowWidth*4*maxSupersegments*numLayers * 4)
            } else {
                MemoryUtil.memCalloc(windowHeight*windowWidth*4*maxSupersegments*numLayers)
            }
            val outputSubDepthBuffer = if(separateDepth) {
//                MemoryUtil.memCalloc(windowHeight*windowWidth*2*maxSupersegments*2 * 2)
                MemoryUtil.memCalloc(windowHeight*windowWidth*2*maxSupersegments*2)
            } else {
                MemoryUtil.memCalloc(0)
            }

//            val numGridCells = 2.0.pow(numOctreeLayers)
            val numGridCells = Vector3f(windowWidth.toFloat() / 8f, windowHeight.toFloat() / 8f, maxSupersegments.toFloat())
//            val numGridCells = Vector3f(256f, 256f, 256f)
            val lowestLevel = MemoryUtil.memCalloc(numGridCells.x.toInt() * numGridCells.y.toInt() * numGridCells.z.toInt() * 4)

            val outputSubVDIColor: Texture
            val outputSubVDIDepth: Texture
            val gridCells: Texture

            outputSubVDIColor = if(colors32bit) {
                Texture.fromImage(
                    Image(outputSubColorBuffer, numLayers * maxSupersegments, windowHeight, windowWidth), usage = hashSetOf(
                        Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture), type = FloatType(), channels = 4, mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)
            } else {
                Texture.fromImage(
                    Image(outputSubColorBuffer, numLayers * maxSupersegments, windowHeight, windowWidth), usage = hashSetOf(
                        Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
            }

            volumeManager.customTextures.add("OutputSubVDIColor")
            volumeManager.material().textures["OutputSubVDIColor"] = outputSubVDIColor

            if(separateDepth) {
                outputSubVDIDepth = Texture.fromImage(
                    Image(outputSubDepthBuffer, maxSupersegments, windowHeight, windowWidth),  usage = hashSetOf(
                        Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture), type = UnsignedShortType(), channels = 2, mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)
//                    Image(outputSubDepthBuffer, 2*maxSupersegments, windowHeight, windowWidth),  usage = hashSetOf(
//                        Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture), type = FloatType(), channels = 1, mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)
                volumeManager.customTextures.add("OutputSubVDIDepth")
                volumeManager.material().textures["OutputSubVDIDepth"] = outputSubVDIDepth
            }

            gridCells = Texture.fromImage(Image(lowestLevel, numGridCells.x.toInt(), numGridCells.y.toInt(), numGridCells.z.toInt()), channels = 1, type = UnsignedIntType(),
                usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
            volumeManager.customTextures.add("OctreeCells")
            volumeManager.material().textures["OctreeCells"] = gridCells

            hub.add(volumeManager)

            val compute = RichNode()
            compute.setMaterial(ShaderMaterial(Shaders.ShadersFromFiles(arrayOf("GridCellsToZero.comp"), this@VolumeFromFileExample::class.java)))

            compute.metadata["ComputeMetadata"] = ComputeMetadata(
                workSizes = Vector3i(numGridCells.x.toInt(), numGridCells.y.toInt(), 1),
                invocationType = InvocationType.Permanent
            )

            compute.material().textures["GridCells"] = gridCells

            scene.addChild(compute)

        } else {
            val volumeManager = VolumeManager(hub,
                useCompute = true,
                customSegments = hashMapOf(
                    SegmentType.FragmentShader to SegmentTemplate(
                        this.javaClass,
                        "ComputeRaycast.comp",
                        "intersectBoundingBox", "vis", "localNear", "localFar", "SampleVolume", "Convert", "Accumulate"),
                ))
            volumeManager.customTextures.add("OutputRender")

            val outputBuffer = MemoryUtil.memCalloc(windowWidth*windowHeight*4)
            val outputTexture = Texture.fromImage(Image(outputBuffer, windowWidth, windowHeight), usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
            volumeManager.material().textures["OutputRender"] = outputTexture

            hub.add(volumeManager)

            val plane = FullscreenObject()
            scene.addChild(plane)
            plane.material().textures["diffuse"] = volumeManager.material().textures["OutputRender"]!!
        }


        with(cam) {
            spatial {
                position = Vector3f(3.174E+0f, -1.326E+0f, -2.554E+0f)
                rotation = Quaternionf(-1.276E-2,  9.791E-1,  6.503E-2, -1.921E-1)

                position = Vector3f(4.004E+0f, -1.398E+0f, -2.170E+0f) //this is the actual 0 degree
                rotation = Quaternionf(-1.838E-2,  9.587E-1,  6.367E-2, -2.767E-1)
            }
            perspectiveCamera(50.0f, windowWidth, windowHeight)
            cam.farPlaneDistance = 20.0f

            scene.addChild(this)
        }

        val shell = Box(Vector3f(10.0f, 10.0f, 10.0f), insideNormals = true)
        shell.material {
            cullingMode = Material.CullingMode.None
            diffuse = Vector3f(0.2f, 0.2f, 0.2f)
            specular = Vector3f(0.0f)
            ambient = Vector3f(0.0f)
        }
        scene.addChild(shell)
        shell.visible = false

        val datasetPath = Paths.get("/home/aryaman/Datasets/Volume/${dataset}")

        val tf = TransferFunction()
        with(tf) {
            if(dataset == "Stagbeetle" || dataset == "Stagbeetle_divided") {
                addControlPoint(0.0f, 0.0f)
                addControlPoint(0.005f, 0.0f)
                addControlPoint(0.01f, 0.1f)
            } else if (dataset == "Kingsnake") {
                addControlPoint(0.0f, 0.0f)
                addControlPoint(0.4f, 0.0f)
                addControlPoint(0.5f, 0.5f)
            } else if (dataset == "Beechnut") {
                addControlPoint(0.0f, 0.0f)
                addControlPoint(0.20f, 0.0f)
                addControlPoint(0.25f, 0.2f)
                addControlPoint(0.35f, 0.0f)
            } else if (dataset == "Simulation") {
                addControlPoint(0.0f, 0.0f)
                addControlPoint(0.2f, 0.0f)
                addControlPoint(0.4f, 0.0f)
                addControlPoint(0.45f, 0.1f)
                addControlPoint(0.5f, 0.10f)
                addControlPoint(0.55f, 0.1f)
                addControlPoint(0.85f, 0.5f)
                addControlPoint(0.87f, 0.05f)
                addControlPoint(0.9f, 0.05f)
                addControlPoint(0.91f, 0.0f)
                addControlPoint(1.0f, 0.0f)
            } else {
                logger.info("Using a standard transfer function")
                TransferFunction.ramp(0.1f, 0.5f)
            }
        }

        val pixelToWorld = (0.0075f * 512f) / volumeDims.x

        val parent = RichNode()

        var prev_slices = 0f
        var current_slices = 0f

        var prevIndex = 0f

        for(i in 1..num_parts) {
            val volume = fromPathRaw(Paths.get("$datasetPath/Part$i"), is16bit)
            volume.name = "volume"
            volume.colormap = Colormap.get("hot")
            volume.origin = Origin.FrontBottomLeft
            val source = (volume.ds.sources[0].spimSource as TransformedSource).wrappedSource as? BufferSource<*>
            //volume.converterSetups.first().setDisplayRange(10.0, 220.0)
            current_slices = source!!.depth.toFloat()
            logger.info("current slices: $current_slices")
            volume.pixelToWorldRatio = pixelToWorld
            volume.transferFunction = tf
//            volume.spatial().position = Vector3f(2.0f, 6.0f, 4.0f - ((i - 1) * ((volumeDims.z / num_parts) * pixelToWorld)))
//            if(i > 1) {
//            val temp = Vector3f(volumeList.lastOrNull()?.spatial()?.position?: Vector3f(0f)) - Vector3f(0f, 0f, (prev_slices/2f + current_slices/2f) * pixelToWorld)
            val temp = Vector3f(0f, 0f, 1.0f * (prevIndex) * pixelToWorld)
            volume.spatial().position = temp
//            if(num_parts > 1) {
//                volume.spatial().scale = Vector3f(1.0f, 1.0f, 2.0f)
//            }
//            }
            prevIndex += current_slices
            logger.info("volume slice $i position set to $temp")
            prev_slices = current_slices
//            volume.spatial().updateWorld(true)
            parent.addChild(volume)
//            println("Volume model matrix is: ${Matrix4f(volume.spatial().model).invert()}")
            volumeList.add(volume)
        }

//        parent.spatial().position = Vector3f(2.0f, 6.0f, 4.0f) - Vector3f(0.0f, 0.0f, volumeList.map { it.spatial().position.z }.sum()/2.0f)
        parent.spatial().position = Vector3f(0f)
//        cam.spatial().position = Vector3f(0f)

        scene.addChild(parent)

        val middle_index = if(num_parts % 2 == 0) {
            (num_parts / 2) - 1
        } else {
            (num_parts + 1) / 2 - 1
        }

        val pivot = Box(Vector3f(20.0f))
        pivot.material().diffuse = Vector3f(0.0f, 1.0f, 0.0f)
        pivot.spatial().position = Vector3f(volumeDims.x/2.0f, volumeDims.y/2.0f, volumeDims.z/2.0f)
        parent.children.first().addChild(pivot)
        parent.spatial().updateWorld(true)
        cam.target = pivot.spatial().worldPosition(Vector3f(0.0f))
        camTarget = pivot.spatial().worldPosition(Vector3f(0.0f))

        pivot.visible = false

        logger.info("Setting target to: ${pivot.spatial().worldPosition(Vector3f(0.0f))}")

        val lights = (0 until 3).map {
            PointLight(radius = 15.0f)
        }

        lights.mapIndexed { i, light ->
            light.spatial().position = Vector3f(2.0f * i - 4.0f,  i - 1.0f, 0.0f)
            light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
                    light.intensity = 0.5f
            scene.addChild(light)
        }

        thread {
            if (generateVDIs) {
                manageVDIGeneration()
            }
        }

        thread {
            if(benchmarking) {
                doBenchmarks()
            }
        }

        thread {
            while(true)
            {
                Thread.sleep(2000)
                println("${cam.spatial().position}")
                println("${cam.spatial().rotation}")
            }
        }
        thread {
            Thread.sleep(closeAfter)
            renderer?.shouldClose = true
        }

    }
//    override fun inputSetup() {
//        super.inputSetup()
//
//
//    }


    private fun doBenchmarks() {
        val r = (hub.get(SceneryElement.Renderer) as Renderer)

        while(!r.firstImageReady) {
            Thread.sleep(200)
        }

        val rotationInterval = 10f
        var totalRotation = 0f

        for(i in 1..4) {
            val path = "benchmarking/${dataset}/View${viewNumber}/volume_rendering/reference${windowWidth}_${windowHeight}_${totalRotation.toInt()}"
            // take screenshot and wait for async writing
            r.screenshot("$path.png")
            Thread.sleep(1000L)
            stats.clear("Renderer.fps")

            // collect data for a few secs
            Thread.sleep(5000)

            // write out CSV with fps data
            val fps = stats.get("Renderer.fps")!!
            File("$path.csv").writeText("${fps.avg()};${fps.min()};${fps.max()};${fps.stddev()};${fps.data.size}")

            rotateCamera(10f)
            totalRotation = i * rotationInterval
        }
    }

    private fun rotateCamera(degrees: Float) {
        cam.targeted = true
        val frameYaw = degrees / 180.0f * Math.PI.toFloat()
        val framePitch = 0f

        // first calculate the total rotation quaternion to be applied to the camera
        val yawQ = Quaternionf().rotateXYZ(0.0f, frameYaw, 0.0f).normalize()
        val pitchQ = Quaternionf().rotateXYZ(framePitch, 0.0f, 0.0f).normalize()

        logger.info("cam target: ${camTarget}")

        val distance = (camTarget - cam.spatial().position).length()
        cam.spatial().rotation = pitchQ.mul(cam.spatial().rotation).mul(yawQ).normalize()
        cam.spatial().position = camTarget + cam.forward * distance * (-1.0f)
        logger.info("new camera pos: ${cam.spatial().position}")
        logger.info("new camera rotation: ${cam.spatial().rotation}")
        logger.info("camera forward: ${cam.forward}")
    }

    private fun manageVDIGeneration() {
        var subVDIDepthBuffer: ByteBuffer? = null
        var subVDIColorBuffer: ByteBuffer?
        var gridCellsBuff: ByteBuffer?

        while(renderer?.firstImageReady == false) {
//        while(renderer?.firstImageReady == false || volumeManager.shaderProperties.isEmpty()) {
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



//            val camera = volumeManager.getScene()?.activeObserver ?: throw UnsupportedOperationException("No camera found")
            val camera = cam

            val model = volumeList.first().spatial().world

//            val translated = Matrix4f(model).translate(Vector3f(-1f) * volumeList.first().spatial().worldPosition()).translate(volumeList.first().spatial().worldPosition())

            logger.info("The model matrix added to the vdi is: $model.")
//            logger.info(" After translation: $translated")

            if(cnt < 20) {

                logger.info(volumeManager.shaderProperties.keys.joinToString())
//                val vdiData = VDIData(subVDIDepthBuffer!!, subVDIColorBuffer!!, gridCellsBuff!!, VDIMetadata(

                val total_duration = measureNanoTime {
                    val vdiData = VDIData(
                        VDIMetadata(
                            projection = camera.spatial().projection,
                            view = camera.spatial().getTransformation(),
                            volumeDimensions = volumeDims,
                            model = model,
                            nw = volumeList.first().volumeManager.shaderProperties["nw"] as Float,
                            windowDimensions = Vector2i(camera.width, camera.height)
                        )
                    )

                    val duration = measureNanoTime {
                        val file = FileOutputStream(File("${dataset}vdidump$cnt"))
//                    val comp = GZIPOutputStream(file, 65536)
                        VDIDataIO.write(vdiData, file)
                        logger.info("written the dump")
                        file.close()
                    }
                    logger.info("time taken (uncompressed): ${duration}")
                }

                logger.info("total serialization duration: ${total_duration}")

                var fileName = ""
                if(world_abs) {
                    fileName = "${dataset}VDI${cnt}_world_new"
                } else {
                    fileName = "${dataset}VDI${cnt}_ndc"
                }
                if(separateDepth) {
                    SystemHelpers.dumpToFile(subVDIColorBuffer!!, "${fileName}_col")
                    SystemHelpers.dumpToFile(subVDIDepthBuffer!!, "${fileName}_depth")
                    SystemHelpers.dumpToFile(gridCellsBuff!!, "${fileName}_octree")
                } else {
                    SystemHelpers.dumpToFile(subVDIColorBuffer!!, fileName)
                    SystemHelpers.dumpToFile(gridCellsBuff!!, "${fileName}_octree")
                }
                logger.info("Wrote VDI $cnt")
            }
            cnt++
        }
    }

    override fun inputSetup() {
        setupCameraModeSwitching()

        inputHandler?.addBehaviour("rotate_camera", ClickBehaviour { _, _ ->
            rotateCamera(10f)
        })
        inputHandler?.addKeyBinding("rotate_camera", "R")
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            VolumeFromFileExample().main()
        }
    }
}
