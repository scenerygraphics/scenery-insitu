package graphics.scenery.insitu

import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.backends.Shaders
import graphics.scenery.compute.ComputeMetadata
import graphics.scenery.compute.InvocationType
import graphics.scenery.textures.Texture
import graphics.scenery.utils.Image
import graphics.scenery.utils.SystemHelpers
import org.joml.Vector3f
import org.joml.Vector3i
import org.junit.Test
import org.lwjgl.system.MemoryUtil
import org.scijava.ui.behaviour.ClickBehaviour
import java.io.File
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class VDICompositingExample:SceneryBase("VDIComposit", 512, 512) {
    override fun init() {
        renderer = hub.add(SceneryElement.Renderer,
                Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val maxSupersegments = 32

        var buff = ByteArray(windowHeight*windowWidth*maxSupersegments)
        var depthBuff = ByteArray(windowHeight*windowWidth*2*maxSupersegments)

        buff = File("/home/aryaman/Desktop/vdi_files/0:textureVDISetCol-2020-07-10_01.19.49.raw").readBytes()
        depthBuff = File("/home/aryaman/Desktop/vdi_files/0:textureVDISetDepth-2020-07-10_01.19.49.raw").readBytes()

        var colBuffer: ByteBuffer
        var depthBuffer: ByteBuffer
//        colBuffer = ByteBuffer.wrap(buff)
//        depthBuffer = ByteBuffer.wrap(depthBuff)
        colBuffer = MemoryUtil.memCalloc(windowHeight * windowWidth * maxSupersegments * 4)
        depthBuffer = MemoryUtil.memCalloc(windowHeight * windowWidth * maxSupersegments * 4 * 2)

        logger.info("Length of color buffer is ${buff.size} and associated bytebuffer capacity is ${colBuffer.capacity()} it has remaining: ${colBuffer.remaining()}")
        logger.info("Length of depth buffer is ${depthBuff.size} and associated bytebuffer capacity is ${depthBuffer.capacity()} it has remianing ${depthBuffer.remaining()}")
        colBuffer.put(buff).flip()
        depthBuffer.put(depthBuff).flip()


        val maxOutputSupersegments = 50
        val commSize = 2;
        val compute = Box()

        compute.name = "compositor node"

        compute.setMaterial(ShaderMaterial(Shaders.ShadersFromFiles(arrayOf("VDICompositor.comp"), this@VDICompositingExample::class.java)))
        val outputColours = MemoryUtil.memCalloc(maxOutputSupersegments*windowHeight*windowWidth*4 / commSize)
        val compositedVDIColor = Texture.fromImage(Image(outputColours, maxOutputSupersegments, windowHeight,  windowWidth/commSize), usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
        compute.material().textures["CompositedVDIColor"] = compositedVDIColor

        val outputDepths = MemoryUtil.memCalloc(2*maxOutputSupersegments*windowHeight*windowWidth*4 / commSize)
        val compositedVDIDepth = Texture.fromImage(Image(outputDepths, 2*maxOutputSupersegments, windowHeight,  windowWidth/commSize), usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
        compute.material().textures["CompositedVDIDepth"] = compositedVDIDepth

        compute.metadata["ComputeMetadata"] = ComputeMetadata(
                workSizes = Vector3i(windowHeight, windowWidth/commSize, 1)
        )

        compute.material().textures["VDIsColor"] = Texture(Vector3i(maxSupersegments, windowHeight, windowWidth), 4, contents = colBuffer, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
        compute.material().textures["VDIsDepth"] = Texture(Vector3i(maxSupersegments*2, windowHeight, windowWidth), 4, contents = depthBuffer, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
        logger.info("Updated the ip textured")

        compute.visible = true
        scene.addChild(compute)

        val light = PointLight(radius = 15.0f)
        light.position = Vector3f(0.0f, 0.0f, 2.0f)
        light.intensity = 5.0f
        light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
        scene.addChild(light)

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            position = Vector3f(0.0f, 0.0f, 5.0f)
            perspectiveCamera(50.0f, 512, 512)

            scene.addChild(this)
        }

    }

    override fun inputSetup() {
        super.inputSetup()
        //TODO: modify the below for new scenery syntax
//
//        inputHandler?.addBehaviour("save_texture", ClickBehaviour { _, _ ->
//            logger.info("Finding node")
//            val node = scene.find("compositor node") ?: return@ClickBehaviour
//            val texture = node.material.textures["CompositedVDIColor"]!!
//            val textureDepth = node.material.textures["CompositedVDIDepth"]!!
//            val r = renderer ?: return@ClickBehaviour
//            logger.info("Node found, saving texture")
//
//            val result = r.requestTexture(texture) { tex ->
//                logger.info("Received texture")
//
//                tex.contents?.let { buffer ->
//                    logger.info("Dumping to file")
//                    SystemHelpers.dumpToFile(buffer, "/home/aryaman/Desktop/vdi_files/texture-${SystemHelpers.formatDateTime(delimiter = "_")}.raw")
//                    logger.info("File dumped")
//                }
//
//            }
//
//            val resultDepth = r.requestTexture(textureDepth) { tex ->
//                logger.info("Received depth texture")
//
//                tex.contents?.let { buffer ->
//                    logger.info("Dumping to file")
//                    SystemHelpers.dumpToFile(buffer, "/home/aryaman/Desktop/vdi_files/texture-${SystemHelpers.formatDateTime(delimiter = "_")}.raw")
//                    logger.info("File dumped")
//                }
//
//            }
//
//        })
        inputHandler?.addKeyBinding("save_texture", "E")
    }

    @Test
    override fun main() {
        super.main()
    }
}