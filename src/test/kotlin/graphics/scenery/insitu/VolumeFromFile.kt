package graphics.scenery.insitu

import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.controls.TrackedStereoGlasses
import graphics.scenery.attribute.material.Material
import graphics.scenery.backends.vulkan.VulkanRenderer
import graphics.scenery.textures.Texture
import graphics.scenery.utils.Image
import graphics.scenery.utils.SystemHelpers
import graphics.scenery.volumes.Colormap
import graphics.scenery.volumes.TransferFunction
import graphics.scenery.volumes.Volume
import graphics.scenery.volumes.VolumeManager
import net.imglib2.type.numeric.real.FloatType
import org.joml.Quaternionf
import org.joml.Vector3f
import org.lwjgl.system.MemoryUtil
import tpietzsch.shadergen.generate.SegmentTemplate
import tpietzsch.shadergen.generate.SegmentType
import java.nio.ByteBuffer
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Class to test volume rendering performance on data loaded from file
 *
 * @author Aryaman Gupta <argupta@mpi-cbg.de>
 */
class VolumeFromFile: SceneryBase("Volume Rendering", 1832, 1016) {
    var hmd: TrackedStereoGlasses? = null

    lateinit var volumeManager: VolumeManager
    val generateVDIs = false
    val separateDepth = true
    val world_abs = false
    val dataset = "Stagbeetle"
    val closeAfter = 1000000L

    override fun init() {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val raycastShader: String
        val accumulateShader: String
        val compositeShader: String

        if(generateVDIs) {
            raycastShader = "VDIGenerator.comp"
            accumulateShader = "AccumulateVDI.comp"
            compositeShader = "VDICompositor.comp"
            val maxSupersegments = 20
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
                        "intersectBoundingBox", "vis", "SampleVolume", "Convert", "Accumulate",
                    ),
                    SegmentType.Accumulator to SegmentTemplate(
//                                this.javaClass,
                        accumulateShader,
                        "vis", "sampleVolume", "convert",
                    ),
                ),
            )

            val outputSubColorBuffer = MemoryUtil.memCalloc(windowHeight*windowWidth*4*maxSupersegments*numLayers)
            val outputSubDepthBuffer = if(separateDepth) {
                MemoryUtil.memCalloc(windowHeight*windowWidth*4*maxSupersegments*2)
            } else {
                MemoryUtil.memCalloc(0)
            }
            val outputSubVDIColor: Texture
            val outputSubVDIDepth: Texture

            outputSubVDIColor = Texture.fromImage(
                Image(outputSubColorBuffer, numLayers*maxSupersegments, windowHeight, windowWidth), usage = hashSetOf(
                    Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))

            volumeManager.customTextures.add("OutputSubVDIColor")
            volumeManager.material().textures["OutputSubVDIColor"] = outputSubVDIColor

            if(separateDepth) {
                outputSubVDIDepth = Texture.fromImage(
                    Image(outputSubDepthBuffer, 2*maxSupersegments, windowHeight, windowWidth),  usage = hashSetOf(
                        Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture), type = FloatType(), channels = 1, mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)
                volumeManager.customTextures.add("OutputSubVDIDepth")
                volumeManager.material().textures["OutputSubVDIDepth"] = outputSubVDIDepth
            }

            hub.add(volumeManager)

        }

        val cam: Camera = DetachedHeadCamera(hmd)
        with(cam) {
            spatial {
//                position = Vector3f(2.508E+0f, -1.749E+0f,  7.245E+0f)
//                rotation = Quaternionf(2.789E-2,  4.970E-2,  1.388E-3,  9.984E-1)
                position = Vector3f(3.213f, 8.264E-1f, -9.844E-1f)
                rotation = Quaternionf(3.049E-2, 9.596E-1, -1.144E-1, -2.553E-1)
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

        val volume = Volume.fromPathRaw(Paths.get("/home/aryaman/Datasets/Volume/$dataset"), hub)
        volume.name = "volume"
        volume.colormap = Colormap.get("hot")
        volume.spatial {
//            position = Vector3f(0.0f, 4.0f, -3.5f)
//            rotation = rotation.rotateXYZ(0.05f, 0.05f, 0.05f)
            position = Vector3f(2.0f, 6.0f, 4.0f)
//            scale = Vector3f(20.0f, 20.0f, 20.0f)
        }

        if(dataset == "Stagbeetle") {
        volume.pixelToWorldRatio = (0.0075f/832f) * 512f
        } else {
            volume.pixelToWorldRatio = 0.0075f
        }

        logger.info("Local scale: ${volume.localScale()}")
        logger.info("Vertex size: ${volume.vertexSize}")
        logger.info("Vertices: ${volume.vertices.toString()}")
        logger.info("Texcoord size: ${volume.texcoordSize}")
        logger.info("Texcoords: ${volume.texcoords.toString()}")

        with(volume.transferFunction) {
            addControlPoint(0.0f, 0.0f)
            addControlPoint(0.1f, 0.15f)
            addControlPoint(0.2f, 0.4f)
            addControlPoint(0.4f, 0.8f)
            addControlPoint(0.6f, 0.1f)
            addControlPoint(0.8f, 0.0f)
            addControlPoint(1.0f, 0.0f)
        }
        scene.addChild(volume)

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

//        thread {
//            while(true)
//            {
//                Thread.sleep(2000)
//                println("${cam.spatial().position}")
//                println("${cam.spatial().rotation}")
//            }
//        }
        thread {
            Thread.sleep(closeAfter)
            renderer?.shouldClose = true
        }

    }

    private fun manageVDIGeneration() {
        var subVDIDepthBuffer: ByteBuffer? = null
        var subVDIColorBuffer: ByteBuffer?

        while(renderer?.firstImageReady == false) {
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

        var prevAtomic = subvdi.get()

        var cnt = 0
        while (true) {
            while(subvdi.get() == prevAtomic) {
                Thread.sleep(5)
            }
            prevAtomic = subvdi.get()
            subVDIColorBuffer = subVDIColor.contents
            if(separateDepth) {
                subVDIDepthBuffer = subVDIDepth!!.contents
            }

            if(cnt < 20) {
                var fileName = ""
                if(world_abs) {
                    fileName = "${dataset}VDI${cnt}_world_new"
                } else {
                    fileName = "${dataset}VDI${cnt}_ndc"
                }
                if(separateDepth) {
                    SystemHelpers.dumpToFile(subVDIColorBuffer!!, "${fileName}_col")
                    SystemHelpers.dumpToFile(subVDIDepthBuffer!!, "${fileName}_depth")
                } else {
                    SystemHelpers.dumpToFile(subVDIColorBuffer!!, fileName)
                }
                logger.info("Wrote VDI $cnt")
            }
            cnt++
        }
    }

    override fun inputSetup() {
        setupCameraModeSwitching()
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            VolumeFromFile().main()
        }
    }
}
